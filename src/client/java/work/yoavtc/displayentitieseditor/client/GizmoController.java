package work.yoavtc.displayentitieseditor.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Transformation;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload;
import work.yoavtc.displayentitieseditor.net.TransformPayload;
import java.util.ArrayDeque;
import java.util.List;
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
import org.joml.Vector3f;

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
	private static final double HIGHLIGHT_RADIUS = 32;
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
	private static long lastSendMs;
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
		applyPreview();
		send();
		return true;
	}

	// ------------------------------------------------------------------ per-frame

	public static void frame(Camera cam) {
		Minecraft mc = Minecraft.getInstance();
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
		pos = selected.position();
		left = new Quaternionf(t.leftRotation());
		scale = new Vector3f(t.scale());
	}

	private static void applyPreview() {
		applyTo(selected, pos, left, scale);
	}

	private static void applyTo(Display d, Vec3 p, Quaternionf l, Vector3f s) {
		Transformation old = Display.createTransformation(d.getEntityData());
		if (d.getTransformationInterpolationDuration() != 0) {
			d.setTransformationInterpolationDuration(0);
		}
		d.setTransformation(new Transformation(new Vector3f(old.translation()), new Quaternionf(l), new Vector3f(s),
				new Quaternionf(old.rightRotation())));
		d.setPos(p.x, p.y, p.z);
		d.setOldPosAndRot();
	}

	private static void send() {
		sendFor(selected.getId(), pos, left, scale);
	}

	private static void sendFor(int id, Vec3 p, Quaternionf l, Vector3f s) {
		lastSendMs = System.currentTimeMillis();
		ClientPlayNetworking.send(new TransformPayload(id, p.x, p.y, p.z, l.x, l.y, l.z, l.w, s.x, s.y, s.z));
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
				applyPreview();
				send();
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
					for (int i = 0; i < 3; i++) {
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
		applyPreview();
		if (System.currentTimeMillis() - lastSendMs > 50) {
			send();
		}
	}

	private static void endDrag() {
		if (changedFrom(dragStart)) {
			pushUndo(dragStart);
		}
		applyPreview();
		send();
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

	private static Display pickDisplay(Minecraft mc, Vec3 ro, Vec3 rd) {
		AABB search = new AABB(ro, ro.add(rd.scale(RAY_RANGE))).inflate(8);
		List<Display> displays = mc.level.getEntitiesOfClass(Display.class, search);
		Display best = null;
		double bestT = Double.MAX_VALUE;
		for (Display d : displays) {
			AABB box = worldBounds(d).inflate(0.05);
			double t = rayAabb(ro, rd, box);
			if (t >= 0 && t < bestT) {
				bestT = t;
				best = d;
			}
		}
		return best;
	}

	private static Vec3[] localCorners(Display d, Vec3 origin, Transformation t, Quaternionf l, Vector3f s) {
		boolean block = d instanceof Display.BlockDisplay;
		double lo = block ? 0 : -0.5;
		double hi = block ? 1 : 0.5;
		Transformation tr = new Transformation(new Vector3f(t.translation()), l, s, new Quaternionf(t.rightRotation()));
		org.joml.Matrix4fc m = tr.getMatrix();
		Vec3[] out = new Vec3[8];
		for (int i = 0; i < 8; i++) {
			Vector3f v = new Vector3f((float) ((i & 1) == 0 ? lo : hi), (float) ((i & 2) == 0 ? lo : hi),
					(float) ((i & 4) == 0 ? lo : hi));
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
				for (int i = 0; i < 3; i++) {
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

	private static void drawSelectionBox() {
		Transformation t = Display.createTransformation(selected.getEntityData());
		drawBox(localCorners(selected, pos, t, left, scale), 0xFFFFFFFF, 1.5f);
	}

	/** With nothing selected, outline every display near the player; the one under the crosshair is brighter. */
	private static void highlightNearby(Minecraft mc, Vec3 ro, Vec3 rd) {
		Display target = pickDisplay(mc, ro, rd);
		for (Display d : mc.level.getEntitiesOfClass(Display.class, new AABB(ro, ro).inflate(HIGHLIGHT_RADIUS))) {
			Transformation t = Display.createTransformation(d.getEntityData());
			Vec3[] c = localCorners(d, d.position(), t, new Quaternionf(t.leftRotation()), new Vector3f(t.scale()));
			drawBox(c, d == target ? 0xFFFFFFFF : 0xFF55DDFF, d == target ? 2.5f : 1.5f);
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
