package work.yoavtc.displayentitieseditor.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Transformation;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload;
import work.yoavtc.displayentitieseditor.net.TransformPayload;
import work.yoavtc.displayentitieseditor.net.TransformResultPayload;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import work.yoavtc.displayentitieseditor.DisplayEntitiesEditorConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** All editor state and per-frame logic: picking, handle hit-testing, dragging, and gizmo drawing. */
public final class GizmoController {
	public enum Mode {
		TRANSLATE("Translate"), ROTATE("Rotate"), SCALE("Scale");

		public final String label;

		Mode(String label) {
			this.label = label;
		}
	}

	private record Snapshot(int id, Vec3 pos, Quaternionf left, Vector3f scale) {
	}

	private static final int CENTER = 3;
	private static final Vec3[] AXES = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
	private static final int[] COLORS = {0xFFFF3030, 0xFF30DD30, 0xFF3030FF};
	private static final int[] HOVER_COLORS = {0xFFFF9090, 0xFF90FF90, 0xFF9090FF};
	private static final int CENTER_COLOR = 0xFFFFB0B0;
	private static final int CENTER_HOVER = 0xFFFFFFFF;
	private static final int RING_SEGMENTS = 64;
	private static final double HIGHLIGHT_RADIUS = 8;
	/** How far the editing ray travels: nothing beyond this distance from the camera can be picked, hovered or dragged. */
	private static final double RAY_RANGE = 5.0;

	public static boolean active;
	public static Mode mode = Mode.TRANSLATE;
	public static Display selected;

	// live values of the selected entity
	private static Vec3 pos = Vec3.ZERO;
	private static Quaternionf left = new Quaternionf();
	private static Vector3f scale = new Vector3f(1, 1, 1);
	private static Vector3f translation = new Vector3f();
	private static Quaternionf rightRot = new Quaternionf();

	private static int hover = -1;
	private static int dragHandle = -1;
	private static Snapshot dragStart;
	private static Vec3 dragP0 = Vec3.ZERO;
	private static double dragT0;
	private static double dragAngle0;
	private static double dragLastAngle;
	private static Vec3 dragAxis = Vec3.ZERO;
	private static Vec3 dragOrigin = Vec3.ZERO;
	private static Vec3 dragNormal = Vec3.ZERO;

	private static boolean lastRmb;
	private static boolean lastDelete;
	private static boolean lastUndo;
	private static int pendingSelectId = -1;
	private static long pendingSelectUntilMs;

	private static final ArrayDeque<Snapshot> UNDO = new ArrayDeque<>();

	private GizmoController() {
	}

	// ------------------------------------------------------------------ public API

	public static void setActive(boolean on) {
		active = on;
		if (!active) {
			cancelDrag();
			selected = null;
			hover = -1;
		}
	}

	public static void setMode(Mode newMode) {
		if (dragHandle < 0) {
			mode = newMode;
		}
	}

	/** Translate -> Rotate -> Scale -> Translate. */
	public static void cycleMode() {
		Mode[] modes = Mode.values();
		setMode(modes[(mode.ordinal() + 1) % modes.length]);
	}

	/** Amounts one scroll notch changes the hovered handle by (rotation uses the same fraction of a quarter turn). */
	private static final double[] NUDGE_STEPS = {0.01, 0.1, 0.25, 0.5};
	private static int nudgeIndex = 1;

	public static double nudgeStep() {
		return NUDGE_STEPS[nudgeIndex];
	}

	/** Human readable nudge amount for the HUD, e.g. "0.25". */
	public static String nudgeLabel() {
		return String.valueOf(nudgeStep());
	}

	public static void cycleNudge() {
		nudgeIndex = (nudgeIndex + 1) % NUDGE_STEPS.length;
	}

	public static void selectById(int entityId) {
		pendingSelectId = entityId;
		pendingSelectUntilMs = System.currentTimeMillis() + 3000;
	}

	/** Index of the hovered handle (-1 for none); exposed for the game tests. */
	public static int hoverHandle() {
		return hover;
	}

	/** Returns label parts: {prefix, x, y, z} for the HUD readout, or null when nothing is selected. */
	public static String[] readout() {
		if (!active || selected == null) {
			return null;
		}
		return switch (mode) {
			case TRANSLATE -> new String[] {"Position:", fmt(pos.x), fmt(pos.y), fmt(pos.z)};
			case ROTATE -> {
				Vector3f e = left.getEulerAnglesXYZ(new Vector3f());
				yield new String[] {"Rotation:", fmt(Math.toDegrees(e.x)), fmt(Math.toDegrees(e.y)), fmt(Math.toDegrees(e.z))};
			}
			case SCALE -> new String[] {"Scale:", fmt(scale.x), fmt(scale.y), fmt(scale.z)};
		};
	}

	/** Mouse wheel nudge on the hovered handle. Returns true if the scroll was consumed. */
	public static boolean onScroll(double dy) {
		if (!active || selected == null || dragHandle >= 0 || hover < 0 || hover == CENTER || dy == 0) {
			return false;
		}
		int steps = dy > 0 ? 1 : -1;
		beginUndo();
		Vec3 o = origin();
		switch (mode) {
			case TRANSLATE -> {
				pos = pos.add(AXES[hover].scale(steps * nudgeStep()));
			}
			case ROTATE -> {
				double deg = nudgeStep() * 90; // 0.01 -> 0.9 deg, 0.1 -> 9 deg, 0.25 -> 22.5 deg, 0.5 -> 45 deg
				Quaternionf delta = new Quaternionf().rotationAxis((float) Math.toRadians(deg * steps),
						(float) AXES[hover].x, (float) AXES[hover].y, (float) AXES[hover].z);
				left = delta.mul(left, new Quaternionf()).normalize();
				pos = o.subtract(pivotOffset(left, scale));
			}
			case SCALE -> {
				float step = (float) nudgeStep();
				scale.setComponent(hover, Math.max(0.001f, scale.get(hover) + step * steps));
				pos = o.subtract(pivotOffset(left, scale));
			}
		}
		commitUndo();
		applyPreview(NUDGE_SEND_DELAY_MS);
		return true;
	}

	// ------------------------------------------------------------------ per-frame

	public static void frame(Camera cam) {
		Minecraft mc = Minecraft.getInstance();
		updateLocalEdits();
		if (!active || mc.player == null || mc.level == null || !mc.mouseHandler.isMouseGrabbed()) {
			if (dragHandle >= 0) {
				cancelDrag();
			}
			lastRmb = false;
			return;
		}
		resolvePendingSelect(mc);
		if (selected != null && (selected.isRemoved() || selected.level() != mc.level)) {
			selected = null;
			cancelDrag();
		}

		Vec3 ro = cam.position();
		Vector3f f = new Vector3f(0, 0, -1).rotate(cam.rotation());
		Vec3 rd = new Vec3(f.x, f.y, f.z).normalize();

		boolean rmb = mc.options.keyUse.isDown() || mc.options.keyAttack.isDown();
		boolean rmbPressed = rmb && !lastRmb;
		lastRmb = rmb;

		if (rmbPressed && mc.options.keyShift.isDown()) {
			Display clicked = pickDisplay(mc, ro, rd);
			if (clicked != null) {
				mc.execute(() -> mc.setScreenAndShow(new QuickMenuScreen(clicked)));
				return;
			}
		}

		handleHotkeys();

		if (selected != null) {
			if (dragHandle < 0) {
				loadFromEntity();
				hover = pickHandle(ro, rd);
			}
			if (dragHandle >= 0) {
				if (rmb) {
					updateDrag(ro, rd);
				} else {
					endDrag();
				}
			} else if (rmbPressed && hover >= 0) {
				beginDrag(ro, rd, hover);
			}
		} else {
			hover = -1;
		}

		if (selected == null || (rmbPressed && dragHandle < 0 && hover < 0)) {
			if (rmbPressed && dragHandle < 0 && hover < 0) {
				Display hit = pickDisplay(mc, ro, rd);
				if (hit != selected) {
					selected = hit;
					if (selected != null) {
						loadFromEntity();
					}
				}
			}
		}

		if (selected != null) {
			draw(ro);
		} else {
			highlightNearby(mc, ro, rd);
		}
	}

	private static void resolvePendingSelect(Minecraft mc) {
		if (pendingSelectId < 0) {
			return;
		}
		Entity e = mc.level.getEntity(pendingSelectId);
		if (e instanceof Display d) {
			selected = d;
			cancelDrag();
			loadFromEntity();
			pendingSelectId = -1;
		} else if (System.currentTimeMillis() > pendingSelectUntilMs) {
			pendingSelectId = -1;
		}
	}

	private static void handleHotkeys() {
		boolean undo = ctrl() && DisplayEntitiesEditorClient.undoKey.isDown();
		boolean delete = DisplayEntitiesEditorClient.deleteKey.isDown();
		if (selected != null && dragHandle < 0 && delete && !lastDelete) {
			ClientPlayNetworking.send(new QuickActionPayload(selected.getId(), QuickActionPayload.Action.DELETE));
			selected = null;
		}
		if (undo && !lastUndo && dragHandle < 0) {
			undo();
		}
		lastDelete = delete;
		lastUndo = undo;
	}

	// ------------------------------------------------------------------ entity state

	/** Text displays are flat, so they have no depth (local Z) axis to scale. */
	private static boolean hasDepth() {
		return !(selected instanceof Display.TextDisplay);
	}

	/** World offset from the entity position to the visual centre of the display (the gizmo pivot). */
	private static Vec3 pivotOffset(Quaternionf l, Vector3f s) {
		float c = selected instanceof Display.BlockDisplay ? 0.5f : 0f;
		Vector3f v = new Vector3f(c, c, c);
		new org.joml.Matrix4f().translation(translation).rotate(l).scale(s).rotate(rightRot).transformPosition(v);
		return new Vec3(v.x, v.y, v.z);
	}

	/** World position of the gizmo pivot (visual centre of the selected display). */
	public static Vec3 pivotWorld() {
		return origin();
	}

	private static Vec3 origin() {
		return pos.add(pivotOffset(left, scale));
	}

	private static void loadFromEntity() {
		Transformation t = Display.createTransformation(selected.getEntityData());
		translation = new Vector3f(t.translation());
		rightRot = new Quaternionf(t.rightRotation());
		LocalEdit e = localEdit(selected);
		if (e != null) {
			// the entity may still be easing toward the edit; the gizmo works with where it is going
			pos = e.pos;
			left = new Quaternionf(e.left);
			scale = new Vector3f(e.scale);
			return;
		}
		pos = selected.position();
		left = new Quaternionf(t.leftRotation());
		scale = new Vector3f(t.scale());
	}

	// ------------------------------------------------------------------ local edits, confirmed by the server

	/** Passed as a send delay: keep the change local for now (a drag in progress); it is sent when the drag ends. */
	private static final long HOLD = -1;
	/** Scroll nudges in quick succession are sent as one change once the wheel has been still this long. */
	private static final long NUDGE_SEND_DELAY_MS = 150;
	/** If the server never answers (display gone or out of reach), fall back to its last known state after this long. */
	private static final long CONFIRM_TIMEOUT_MS = 3000;
	/**
	 * Time constant of the visual smoothing, in seconds: each frame the drawn display closes the same fraction of
	 * the gap to the edit target (about 95% after three time constants), independent of the frame rate.
	 */
	private static final double SMOOTHING_SECONDS = 0.03;

	/**
	 * A display this client changed and the server has not confirmed yet. Edits show up locally right away and only
	 * the end result is sent. Until the server answers, this state is kept on the entity, so older updates the
	 * server is still syncing to us cannot pull the preview back.
	 * <p>
	 * What is drawn ("shown") eases toward the edit target instead of jumping to it, which hides mouse jitter and
	 * snapping steps. The target is what the gizmo works with and what is sent to the server.
	 */
	private static final class LocalEdit {
		final Display display;
		/** The display as the server last had it, shown again if the server never answers. */
		Vec3 basePos;
		Quaternionf baseLeft;
		Vector3f baseScale;
		/** The edit target. */
		Vec3 pos;
		Quaternionf left;
		Vector3f scale;
		/** What is drawn right now; the pivot (visual centre) is eased rather than the position, so rotations stay centred. */
		Vec3 shownPivot;
		final Quaternionf shownLeft;
		final Vector3f shownScale;
		long lastStepNanos = System.nanoTime();
		/** A change exists that the server has not been sent yet. */
		boolean unsent;
		/** When the unsent change goes out (Long.MAX_VALUE: held until the drag ends). */
		long sendAtMs = Long.MAX_VALUE;
		/** Sequence number of the last send; the server's answer carries it back. */
		int sentSeq;
		long sentAtMs;
		/** The server answered the last send; the entry is dropped as soon as the drawn state has caught up. */
		boolean confirmed;

		LocalEdit(Display d) {
			display = d;
			Transformation t = Display.createTransformation(d.getEntityData());
			basePos = d.position();
			baseLeft = new Quaternionf(t.leftRotation());
			baseScale = new Vector3f(t.scale());
			pos = basePos;
			left = new Quaternionf(baseLeft);
			scale = new Vector3f(baseScale);
			shownLeft = new Quaternionf(baseLeft);
			shownScale = new Vector3f(baseScale);
			shownPivot = basePos.add(pivotOffsetFor(d, shownLeft, shownScale));
		}

		void retarget(Vec3 p, Quaternionfc l, Vector3fc s) {
			pos = p;
			left = new Quaternionf(l);
			scale = new Vector3f(s);
		}
	}

	/** World offset from a display's position to its visual centre, for a given left rotation and scale. */
	private static Vec3 pivotOffsetFor(Display d, Quaternionfc l, Vector3fc s) {
		Transformation t = Display.createTransformation(d.getEntityData());
		float c = d instanceof Display.BlockDisplay ? 0.5f : 0f;
		Vector3f v = new Vector3f(c, c, c);
		new org.joml.Matrix4f().translation(t.translation()).rotate(l).scale(s).rotate(t.rightRotation()).transformPosition(v);
		return new Vec3(v.x, v.y, v.z);
	}

	/**
	 * Moves what is drawn one frame's worth toward the target and writes it onto the entity.
	 * Returns true once it has arrived (it then shows the target exactly).
	 */
	private static boolean step(LocalEdit e) {
		long now = System.nanoTime();
		double dt = Math.max(0, (now - e.lastStepNanos) / 1.0e9);
		e.lastStepNanos = now;
		Vec3 targetPivot = e.pos.add(pivotOffsetFor(e.display, e.left, e.scale));
		float a = (float) (1 - Math.exp(-dt / SMOOTHING_SECONDS));
		e.shownPivot = e.shownPivot.lerp(targetPivot, a);
		e.shownLeft.slerp(e.left, a).normalize();
		e.shownScale.lerp(e.scale, a);
		boolean arrived = e.shownPivot.distanceToSqr(targetPivot) < 1.0e-8
				&& Math.abs(e.shownLeft.dot(e.left)) > 1 - 1.0e-7f
				&& e.shownScale.distanceSquared(e.scale) < 1.0e-10f;
		if (arrived) {
			e.shownPivot = targetPivot;
			e.shownLeft.set(e.left);
			e.shownScale.set(e.scale);
			writeLocal(e.display, e.pos, e.left, e.scale);
		} else {
			writeLocal(e.display, e.shownPivot.subtract(pivotOffsetFor(e.display, e.shownLeft, e.shownScale)), e.shownLeft, e.shownScale);
		}
		return arrived;
	}

	private static final Map<Integer, LocalEdit> LOCAL_EDITS = new HashMap<>();
	private static int nextSeq = 1;

	private static void applyPreview(long sendDelayMs) {
		applyTo(selected, pos, left, scale, sendDelayMs);
	}

	/** Shows a change locally right away, and sends it to the server after {@code sendDelayMs} (or later, for {@link #HOLD}). */
	private static void applyTo(Display d, Vec3 p, Quaternionf l, Vector3f s, long sendDelayMs) {
		LocalEdit e = LOCAL_EDITS.get(d.getId());
		if (e == null || e.display != d) {
			e = new LocalEdit(d);
			LOCAL_EDITS.put(d.getId(), e);
		}
		e.retarget(p, l, s);
		e.unsent = true;
		e.confirmed = false;
		e.sendAtMs = sendDelayMs < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + sendDelayMs;
		step(e);
		if (sendDelayMs == 0) {
			sendNow(e);
		}
	}

	private static void sendNow(LocalEdit e) {
		e.unsent = false;
		e.sendAtMs = Long.MAX_VALUE;
		e.sentSeq = nextSeq++;
		e.sentAtMs = System.currentTimeMillis();
		Vec3 p = e.pos;
		Quaternionf l = e.left;
		Vector3f s = e.scale;
		ClientPlayNetworking.send(new TransformPayload(e.display.getId(), p.x, p.y, p.z, l.x, l.y, l.z, l.w, s.x, s.y, s.z, e.sentSeq));
	}

	/**
	 * Sends changes that are due, gives up on answers that never came, advances the smoothing, and puts every
	 * unconfirmed change back on its entity in case a server update overwrote it. Runs every client tick (before
	 * entities tick) and every frame.
	 */
	public static void updateLocalEdits() {
		if (LOCAL_EDITS.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		for (Iterator<LocalEdit> it = LOCAL_EDITS.values().iterator(); it.hasNext();) {
			LocalEdit e = it.next();
			if (mc.level == null || e.display.isRemoved() || e.display.level() != mc.level) {
				it.remove();
				continue;
			}
			if (e.unsent && now >= e.sendAtMs) {
				sendNow(e);
			}
			if (!e.unsent && !e.confirmed && now - e.sentAtMs > CONFIRM_TIMEOUT_MS) {
				// no answer: ease back to what the server last confirmed
				e.retarget(e.basePos, e.baseLeft, e.baseScale);
				e.confirmed = true;
			}
			if (step(e) && e.confirmed) {
				it.remove();
			}
		}
	}

	/** The edit target of a display with an unfinished local edit (what the gizmo works with), or null. */
	private static LocalEdit localEdit(Display d) {
		LocalEdit e = LOCAL_EDITS.get(d.getId());
		return e != null && e.display == d ? e : null;
	}

	/** The server handled one of our requests: adopt its state, unless a newer local change is still on its way. */
	public static void onServerResult(TransformResultPayload r) {
		Vec3 serverPos = new Vec3(r.x(), r.y(), r.z());
		Quaternionf serverLeft = new Quaternionf(r.qx(), r.qy(), r.qz(), r.qw());
		Vector3f serverScale = new Vector3f(r.sx(), r.sy(), r.sz());
		LocalEdit e = LOCAL_EDITS.get(r.entityId());
		if (e != null) {
			if (e.unsent || e.sentSeq != r.seq()) {
				return; // the answer to the newer change will settle it
			}
			// Usually the same as the target; if the server refused or adjusted the edit, the display eases to the truth.
			// The entry stays until the drawn state has caught up, then updateLocalEdits drops it.
			e.retarget(serverPos, serverLeft, serverScale);
			e.basePos = serverPos;
			e.baseLeft = new Quaternionf(serverLeft);
			e.baseScale = new Vector3f(serverScale);
			e.confirmed = true;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !(mc.level.getEntity(r.entityId()) instanceof Display d)) {
			return;
		}
		if (e == null && dragHandle >= 0 && d == selected) {
			return; // a drag just started on this display; its own result will follow
		}
		writeLocal(d, serverPos, serverLeft, serverScale);
	}

	/** Writes a state onto the client-side entity so it is drawn that way in this very frame. */
	private static void writeLocal(Display d, Vec3 p, Quaternionf l, Vector3f s) {
		Transformation old = Display.createTransformation(d.getEntityData());
		if (!new Quaternionf(old.leftRotation()).equals(l) || !new Vector3f(old.scale()).equals(s)) {
			if (d.getTransformationInterpolationDuration() != 0) {
				d.setTransformationInterpolationDuration(0);
			}
			d.setTransformation(new Transformation(new Vector3f(old.translation()), new Quaternionf(l), new Vector3f(s),
					new Quaternionf(old.rightRotation())));
			// A display only rebuilds what it draws on its next tick; do it now so rotation and scale move in the same
			// frame as the position instead of trailing it by up to a tick.
			d.renderState = d.createFreshRenderState();
		}
		if (!d.position().equals(p)) {
			d.setPos(p.x, p.y, p.z);
		}
		d.setOldPosAndRot();
		// Server position updates interpolate (smooth for other viewers); keep them from dragging our local state around.
		d.getInterpolation().cancel();
	}

	// ------------------------------------------------------------------ undo

	private static Snapshot snapshot() {
		return new Snapshot(selected.getId(), pos, new Quaternionf(left), new Vector3f(scale));
	}

	private static Snapshot pendingUndo;

	private static void beginUndo() {
		pendingUndo = snapshot();
	}

	private static void commitUndo() {
		if (pendingUndo != null && changedFrom(pendingUndo)) {
			pushUndo(pendingUndo);
		}
		pendingUndo = null;
	}

	private static boolean changedFrom(Snapshot s) {
		return !s.pos.equals(pos) || !s.left.equals(left, 1.0e-6f) || !s.scale.equals(scale, 1.0e-6f);
	}

	private static void pushUndo(Snapshot s) {
		UNDO.push(s);
		while (UNDO.size() > 100) {
			UNDO.removeLast();
		}
	}

	private static void undo() {
		Minecraft mc = Minecraft.getInstance();
		while (!UNDO.isEmpty()) {
			Snapshot s = UNDO.pop();
			if (mc.level != null && mc.level.getEntity(s.id) instanceof Display d) {
				selected = d;
				pos = s.pos;
				left = new Quaternionf(s.left);
				scale = new Vector3f(s.scale);
				applyPreview(0);
				return;
			}
		}
	}

	// ------------------------------------------------------------------ dragging

	private static void beginDrag(Vec3 ro, Vec3 rd, int handle) {
		dragHandle = handle;
		dragStart = snapshot();
		dragOrigin = origin();
		dragNormal = rd;
		dragAxis = mode == Mode.SCALE && handle < 3 ? localAxis(handle) : handle < 3 ? AXES[handle] : Vec3.ZERO;
		if (handle == CENTER) {
			Vec3 p = rayPlane(ro, rd, dragOrigin, dragNormal);
			dragP0 = p == null ? dragOrigin : p;
			dragT0 = Math.max(1.0e-4, dragP0.subtract(dragOrigin).length());
			return;
		}
		if (mode == Mode.ROTATE) {
			double[] r = ringClosest(ro, rd, dragOrigin, handle, gizmoSize(ro.distanceTo(dragOrigin)) * 0.9, 0, Math.PI, true);
			dragAngle0 = r == null ? 0 : r[0];
			dragLastAngle = dragAngle0;
		} else {
			double[] c = closest(ro, rd, dragOrigin, dragAxis, true);
			dragT0 = c == null ? 0 : c[0];
		}
	}

	private static void updateDrag(Vec3 ro, Vec3 rd) {
		Snapshot s = dragStart;
		Vec3 o0 = dragOrigin;
		switch (mode) {
			case TRANSLATE -> {
				double step = translateStep();
				if (dragHandle == CENTER) {
					Vec3 p = rayPlane(ro, rd, o0, dragNormal);
					if (p == null) {
						return;
					}
					Vec3 np = s.pos.add(p.subtract(dragP0));
					pos = new Vec3(snap(np.x, step), snap(np.y, step), snap(np.z, step));
				} else {
					double[] c = closest(ro, rd, o0, dragAxis, true);
					if (c == null) {
						return;
					}
					Vec3 np = s.pos.add(dragAxis.scale(c[0] - dragT0));
					pos = switch (dragHandle) {
						case 0 -> new Vec3(snap(np.x, step), s.pos.y, s.pos.z);
						case 1 -> new Vec3(s.pos.x, snap(np.y, step), s.pos.z);
						default -> new Vec3(s.pos.x, s.pos.y, snap(np.z, step));
					};
				}
			}
			case ROTATE -> {
				if (dragHandle == CENTER) {
					return;
				}
				double[] r = ringClosest(ro, rd, o0, dragHandle, gizmoSize(ro.distanceTo(o0)) * 0.9, dragLastAngle, 1.2, true);
				if (r == null) {
					return;
				}
				dragLastAngle = r[0];
				double delta = r[0] - dragAngle0;
				delta = Math.atan2(Math.sin(delta), Math.cos(delta));
				double stepRad = shift() ? 0 : Math.toRadians(ctrl() ? 45 : 15);
				if (stepRad > 0) {
					delta = Math.round(delta / stepRad) * stepRad;
				}
				Quaternionf dq = new Quaternionf().rotationAxis((float) delta,
						(float) AXES[dragHandle].x, (float) AXES[dragHandle].y, (float) AXES[dragHandle].z);
				left = dq.mul(s.left, new Quaternionf()).normalize();
				pos = o0.subtract(pivotOffset(left, s.scale));
			}
			case SCALE -> {
				double step = shift() ? 0 : ctrl() ? 0.25 : 0.0625;
				Vector3f ns = new Vector3f(s.scale);
				if (dragHandle == CENTER) {
					Vec3 p = rayPlane(ro, rd, o0, dragNormal);
					if (p == null) {
						return;
					}
					double factor = p.subtract(o0).length() / dragT0;
					for (int i = 0; i < (hasDepth() ? 3 : 2); i++) {
						ns.setComponent(i, (float) snap(s.scale.get(i) * factor, step));
					}
				} else {
					double[] c = closest(ro, rd, o0, dragAxis, true);
					if (c == null || Math.abs(dragT0) < 1.0e-4) {
						return;
					}
					double factor = c[0] / dragT0;
					ns.setComponent(dragHandle, (float) snap(s.scale.get(dragHandle) * factor, step));
				}
				for (int i = 0; i < 3; i++) {
					if (Math.abs(ns.get(i)) < 0.001f) {
						ns.setComponent(i, 0.001f);
					}
				}
				scale = ns;
				pos = o0.subtract(pivotOffset(s.left, ns));
			}
		}
		// only shown locally while dragging; the end result goes to the server when the drag ends
		applyPreview(HOLD);
	}

	private static void endDrag() {
		if (changedFrom(dragStart)) {
			pushUndo(dragStart);
		}
		applyPreview(0);
		dragHandle = -1;
		dragStart = null;
	}

	private static void cancelDrag() {
		if (dragHandle >= 0 && selected != null && dragStart != null) {
			endDrag();
		}
		dragHandle = -1;
		dragStart = null;
	}

	// ------------------------------------------------------------------ picking

	private static int pickHandle(Vec3 ro, Vec3 rd) {
		Vec3 o = origin();
		double dist = ro.distanceTo(o);
		double size = gizmoSize(dist);
		double thr = 0.035 * dist + 0.01;

		Vec3 toCenter = o.subtract(ro);
		double along = toCenter.dot(rd);
		if (along > 0 && along <= RAY_RANGE && toCenter.subtract(rd.scale(along)).length() < 0.045 * dist && mode != Mode.ROTATE) {
			return CENTER;
		}

		int best = -1;
		double bestScore = Double.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			if (i == 2 && mode == Mode.SCALE && !hasDepth()) {
				continue; // flat text: no depth handle to grab
			}
			if (mode == Mode.ROTATE) {
				double[] r = ringClosest(ro, rd, o, i, size * 0.9, 0, Math.PI, false);
				if (r != null && r[1] < thr && r[1] < bestScore) {
					best = i;
					bestScore = r[1];
				}
			} else {
				Vec3 axis = mode == Mode.SCALE ? localAxis(i) : AXES[i];
				double[] c = closest(ro, rd, o, axis, false);
				if (c == null) {
					continue;
				}
				double lo = mode == Mode.SCALE ? 0.25 * size : -0.1 * size;
				if (c[0] >= lo && c[0] <= size * 1.25 && c[1] < thr && c[1] < bestScore) {
					best = i;
					bestScore = c[1];
				}
			}
		}
		return best;
	}

	/** Visual centre of a display in world space (the mean of its transformed box corners). */
	private static Vec3 displayCentre(Display d) {
		Transformation t = Display.createTransformation(d.getEntityData());
		Vec3[] c = localCorners(d, d.position(), t, new Quaternionf(t.leftRotation()), new Vector3f(t.scale()));
		Vec3 centre = Vec3.ZERO;
		for (Vec3 v : c) {
			centre = centre.add(v);
		}
		return centre.scale(1.0 / c.length);
	}

	/**
	 * The display the player is looking at. Many displays can overlap or sit close together, so instead of taking
	 * the first box the ray enters, take the display whose centre (the marker shown while nothing is selected) is
	 * closest to the look ray. A display qualifies if the ray passes through its box or close to its centre marker.
	 */
	private static Display pickDisplay(Minecraft mc, Vec3 ro, Vec3 rd) {
		AABB search = new AABB(ro, ro.add(rd.scale(RAY_RANGE))).inflate(8);
		List<Display> displays = mc.level.getEntitiesOfClass(Display.class, search);
		Display best = null;
		double bestScore = Double.MAX_VALUE;
		for (Display d : displays) {
			Vec3 toCentre = displayCentre(d).subtract(ro);
			double along = toCentre.dot(rd);
			double boxT = rayAabb(ro, rd, worldBounds(d).inflate(0.05));
			boolean inBox = boxT >= 0;
			if (along < 0 && !inBox) {
				continue;
			}
			double off = toCentre.subtract(rd.scale(along)).length();
			// aiming right at the centre marker counts even if the ray just misses a very small box
			boolean onMarker = along >= 0 && along <= RAY_RANGE && off < 0.05 * along + 0.04;
			if (!inBox && !onMarker) {
				continue;
			}
			// nothing solid may stand between the camera and the display (the box hit, or the marker if only that was hit)
			if (!hasLineOfSight(mc, ro, rd, inBox ? boxT : along)) {
				continue;
			}
			double score = off + along * 1.0e-4; // nearer wins an exact tie
			if (score < bestScore) {
				bestScore = score;
				best = d;
			}
		}
		return best;
	}

	/** True unless a solid block lies meaningfully closer to the camera than the display hit at distance {@code t}. */
	private static boolean hasLineOfSight(Minecraft mc, Vec3 ro, Vec3 rd, double t) {
		net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(ro, ro.add(rd.scale(t)),
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || ro.distanceTo(hit.getLocation()) >= t - 0.1;
	}

	private static Vec3[] localCorners(Display d, Vec3 origin, Transformation t, Quaternionf l, Vector3f s) {
		boolean block = d instanceof Display.BlockDisplay;
		double lo = block ? 0 : -0.5;
		double hi = block ? 1 : 0.5;
		boolean flat = d instanceof Display.TextDisplay; // text has no thickness: keep its box paper thin
		double zlo = flat ? -0.01 : lo;
		double zhi = flat ? 0.01 : hi;
		Transformation tr = new Transformation(new Vector3f(t.translation()), l, s, new Quaternionf(t.rightRotation()));
		org.joml.Matrix4fc m = tr.getMatrix();
		Vec3[] out = new Vec3[8];
		for (int i = 0; i < 8; i++) {
			Vector3f v = new Vector3f((float) ((i & 1) == 0 ? lo : hi), (float) ((i & 2) == 0 ? lo : hi),
					(float) ((i & 4) == 0 ? zlo : zhi));
			m.transformPosition(v);
			out[i] = origin.add(v.x, v.y, v.z);
		}
		return out;
	}

	private static AABB worldBounds(Display d) {
		Transformation t = Display.createTransformation(d.getEntityData());
		Vec3[] c = localCorners(d, d.position(), t, new Quaternionf(t.leftRotation()), new Vector3f(t.scale()));
		double minX = Double.MAX_VALUE, minY = minX, minZ = minX, maxX = -minX, maxY = maxX, maxZ = maxX;
		for (Vec3 v : c) {
			minX = Math.min(minX, v.x);
			minY = Math.min(minY, v.y);
			minZ = Math.min(minZ, v.z);
			maxX = Math.max(maxX, v.x);
			maxY = Math.max(maxY, v.y);
			maxZ = Math.max(maxZ, v.z);
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	// ------------------------------------------------------------------ drawing

	private static double gizmoSize(double dist) {
		return Math.max(0.3, dist * 0.22);
	}

	private static void draw(Vec3 camPos) {
		Vec3 o = origin();
		double dist = camPos.distanceTo(o);
		double size = gizmoSize(dist);
		int active = dragHandle >= 0 ? dragHandle : hover;

		drawSelectionBox();

		switch (mode) {
			case TRANSLATE -> {
				for (int i = 0; i < 3; i++) {
					int color = i == active ? HOVER_COLORS[i] : COLORS[i];
					Vec3 a = AXES[i];
					line(o.subtract(a.scale(300)), o.add(a.scale(300)), color, 1.5f);
					if (i == active) {
						line(o, o.add(a.scale(size)), color, 5f);
					}
					cone(o.add(a.scale(size)), a, size * 0.24, size * 0.075, color);
				}
				centerCube(o, dist, active == CENTER);
			}
			case ROTATE -> {
				for (int i = 0; i < 3; i++) {
					int color = i == active ? HOVER_COLORS[i] : COLORS[i];
					ring(o, i, size * 0.9, color, i == active ? 5f : 3f);
				}
			}
			case SCALE -> {
				for (int i = 0; i < (hasDepth() ? 3 : 2); i++) {
					int color = i == active ? HOVER_COLORS[i] : COLORS[i];
					Vec3 a = localAxis(i);
					line(o.subtract(a.scale(300)), o.add(a.scale(300)), color, 1.5f);
					if (i == active) {
						line(o, o.add(a.scale(size)), color, 5f);
					}
					Vec3 tip = o.add(a.scale(size));
					double h = size * 0.07;
					emit(Gizmos.cuboid(new AABB(tip.x - h, tip.y - h, tip.z - h, tip.x + h, tip.y + h, tip.z + h),
						GizmoStyle.strokeAndFill(color, scaled(1f), color)));
				}
				centerCube(o, dist, active == CENTER);
			}
		}
	}

	private static void centerCube(Vec3 pos, double dist, boolean highlighted) {
		double h = dist * 0.03;
		int color = highlighted ? CENTER_HOVER : CENTER_COLOR;
		emit(Gizmos.cuboid(new AABB(pos.x - h, pos.y - h, pos.z - h, pos.x + h, pos.y + h, pos.z + h),
						GizmoStyle.strokeAndFill(color, scaled(1f), color)));
	}

	/** Outlines the display as it is drawn (which may still be easing toward the edit), so the box hugs it. */
	private static void drawSelectionBox() {
		Transformation t = Display.createTransformation(selected.getEntityData());
		drawBox(localCorners(selected, selected.position(), t, new Quaternionf(t.leftRotation()), new Vector3f(t.scale())),
				0xFFFFFFFF, 1.5f);
	}

	/** With nothing selected, mark the visual centre of every display near the player; the one under the crosshair is bigger and white. */
	/** Line-of-sight results for the markers, refreshed a few times a second so many displays stay cheap. */
	private static final java.util.Map<Integer, Boolean> MARKER_VISIBLE = new java.util.HashMap<>();
	private static long markerVisibleStamp;

	private static void highlightNearby(Minecraft mc, Vec3 ro, Vec3 rd) {
		Display target = pickDisplay(mc, ro, rd);
		long now = System.currentTimeMillis();
		if (now - markerVisibleStamp > 100) {
			MARKER_VISIBLE.clear();
			markerVisibleStamp = now;
		}
		for (Display d : mc.level.getEntitiesOfClass(Display.class, new AABB(ro, ro).inflate(HIGHLIGHT_RADIUS))) {
			Vec3 centre = displayCentre(d);
			double dist = ro.distanceTo(centre);
			if (dist > HIGHLIGHT_RADIUS) {
				continue;
			}
			// only displays the player can actually see (no solid block between the camera and the display's centre)
			boolean visible = d == target || MARKER_VISIBLE.computeIfAbsent(d.getId(),
					id -> dist < 1.0e-3 || hasLineOfSight(mc, ro, centre.subtract(ro).scale(1.0 / dist), dist));
			if (!visible) {
				continue;
			}
			boolean hot = d == target;
			double h = dist * (hot ? 0.025 : 0.016);
			int color = hot ? 0xFFFFFFFF : 0xFF55DDFF;
			emit(Gizmos.cuboid(new AABB(centre.x - h, centre.y - h, centre.z - h, centre.x + h, centre.y + h, centre.z + h),
				GizmoStyle.strokeAndFill(color, scaled(hot ? 2f : 1f), color)));
		}
	}

	private static void drawBox(Vec3[] c, int color, float width) {
		for (int i = 0; i < 8; i++) {
			for (int bit = 1; bit <= 4; bit <<= 1) {
				if ((i & bit) == 0) {
					line(c[i], c[i | bit], color, width);
				}
			}
		}
	}

	/** Gizmos live for one frame: a 1 ms lifetime makes the collector drop them right after they are drawn. */
	private static void emit(GizmoProperties props) {
		props.persistForMillis(1).setAlwaysOnTop();
	}

	/** The widths below are written for the default base width; the config option scales them all. */
	private static float scaled(float width) {
		return width * DisplayEntitiesEditorConfig.lineWidth() / DisplayEntitiesEditorConfig.DEFAULT_LINE_WIDTH;
	}

	private static void line(Vec3 a, Vec3 b, int color, float width) {
		emit(Gizmos.line(a, b, color, scaled(width)));
	}

	private static void ring(Vec3 pos, int axis, double radius, int color, float width) {
		Vec3 u = AXES[(axis + 1) % 3];
		Vec3 v = AXES[(axis + 2) % 3];
		Vec3 prev = null;
		for (int i = 0; i <= RING_SEGMENTS; i++) {
			double a = Math.PI * 2 * i / RING_SEGMENTS;
			Vec3 p = pos.add(u.scale(Math.cos(a) * radius)).add(v.scale(Math.sin(a) * radius));
			if (prev != null) {
				line(prev, p, color, width);
			}
			prev = p;
		}
	}

	private static void cone(Vec3 base, Vec3 dir, double length, double radius, int color) {
		Vec3 u = Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		u = dir.cross(u).normalize();
		Vec3 v = dir.cross(u).normalize();
		Vec3[] fan = new Vec3[10];
		fan[0] = base.add(dir.scale(length));
		for (int i = 0; i < 9; i++) {
			double a = Math.PI * 2 * i / 8;
			fan[i + 1] = base.add(u.scale(Math.cos(a) * radius)).add(v.scale(Math.sin(a) * radius));
		}
		emit(Gizmos.addGizmo((prim, alpha) -> prim.addTriangleFan(fan, color)));
	}

	// ------------------------------------------------------------------ math helpers

	private static Vec3 localAxis(int i) {
		Vector3f v = new Vector3f(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0).rotate(left);
		return new Vec3(v.x, v.y, v.z).normalize();
	}

	/**
	 * Closest point of a ring (circle of the given radius around {@code o}, perpendicular to world axis
	 * {@code axis}) to the view ray, searched within {@code half} radians of {@code centre}.
	 * Works for any view angle, including edge-on rings. Returns {angle, distance to ray, distance along ray}.
	 * With {@code clamp} the ray ends at {@link #RAY_RANGE} but keeps steering the drag; without it points beyond are ignored.
	 */
	private static double[] ringClosest(Vec3 ro, Vec3 rd, Vec3 o, int axis, double radius, double centre, double half, boolean clamp) {
		Vec3 u = AXES[(axis + 1) % 3];
		Vec3 v = AXES[(axis + 2) % 3];
		double[] best = null;
		double step = Math.toRadians(0.5);
		for (int pass = 0; pass < 2; pass++) {
			double from = best == null ? centre - half : best[0] - step;
			double to = best == null ? centre + half : best[0] + step;
			double s = best == null ? step : step / 20;
			double[] found = null;
			for (double a = from; a <= to; a += s) {
				Vec3 p = o.add(u.scale(Math.cos(a) * radius)).add(v.scale(Math.sin(a) * radius));
				Vec3 w = p.subtract(ro);
				double t = w.dot(rd);
				if (t < 0 || (t > RAY_RANGE && !clamp)) {
					continue;
				}
				// while dragging, a point beyond the range is measured against the end of the ray instead
				t = Math.min(t, RAY_RANGE);
				double d = w.subtract(rd.scale(t)).length();
				// prefer the nearer of two almost equally close points (front half of an edge-on ring)
				if (found == null || d < found[1] - 1.0e-3 || (Math.abs(d - found[1]) <= 1.0e-3 && t < found[2])) {
					found = new double[] {a, d, t};
				}
			}
			if (found == null) {
				return best;
			}
			best = found;
		}
		return best;
	}

	/**
	 * Where the view ray meets a drag plane. If that is beyond {@link #RAY_RANGE} (or the ray is parallel to the
	 * plane / points away from it) the ray is simply cut short and the point at the range limit is used, so a
	 * held drag keeps following the crosshair smoothly instead of freezing.
	 */
	private static Vec3 rayPlane(Vec3 ro, Vec3 rd, Vec3 planePoint, Vec3 normal) {
		double denom = rd.dot(normal);
		double t = Math.abs(denom) < 1.0e-5 ? RAY_RANGE : planePoint.subtract(ro).dot(normal) / denom;
		if (t < 0 || t > RAY_RANGE) {
			t = RAY_RANGE;
		}
		return ro.add(rd.scale(t));
	}

	/** Closest point between the view ray and an axis line; returns {parameter along axis, distance}. */
	private static double[] closest(Vec3 ro, Vec3 rd, Vec3 ao, Vec3 ae, boolean clamp) {
		Vec3 w0 = ro.subtract(ao);
		double b = rd.dot(ae);
		double d0 = rd.dot(w0);
		double e0 = ae.dot(w0);
		double denom = 1 - b * b;
		if (denom < 1.0e-6) {
			return null;
		}
		double s = (b * e0 - d0) / denom;
		double t = (e0 - b * d0) / denom;
		if (s < 0 || (s > RAY_RANGE && !clamp)) {
			return null;
		}
		if (s > RAY_RANGE) {
			// the ray ends here: steer by its end point, projected onto the axis
			Vec3 end = ro.add(rd.scale(RAY_RANGE));
			double along = end.subtract(ao).dot(ae);
			return new double[] {along, end.distanceTo(ao.add(ae.scale(along)))};
		}
		Vec3 pr = ro.add(rd.scale(s));
		Vec3 pa = ao.add(ae.scale(t));
		return new double[] {t, pr.distanceTo(pa)};
	}

	private static double rayAabb(Vec3 ro, Vec3 rd, AABB box) {
		double tMin = 0;
		double tMax = RAY_RANGE;
		double[] o = {ro.x, ro.y, ro.z};
		double[] d = {rd.x, rd.y, rd.z};
		double[] lo = {box.minX, box.minY, box.minZ};
		double[] hi = {box.maxX, box.maxY, box.maxZ};
		for (int i = 0; i < 3; i++) {
			if (Math.abs(d[i]) < 1.0e-9) {
				if (o[i] < lo[i] || o[i] > hi[i]) {
					return -1;
				}
			} else {
				double t1 = (lo[i] - o[i]) / d[i];
				double t2 = (hi[i] - o[i]) / d[i];
				tMin = Math.max(tMin, Math.min(t1, t2));
				tMax = Math.min(tMax, Math.max(t1, t2));
				if (tMin > tMax) {
					return -1;
				}
			}
		}
		return tMin;
	}

	private static double snap(double v, double step) {
		return step <= 0 ? v : Math.round(v / step) * step;
	}

	private static double translateStep() {
		return shift() ? 0 : ctrl() ? 0.5 : 0.0625;
	}

	private static boolean ctrl() {
		return InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
	}

	private static boolean shift() {
		return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
	}

	private static String fmt(double v) {
		return String.format(java.util.Locale.ROOT, "%.4f", v);
	}
}
