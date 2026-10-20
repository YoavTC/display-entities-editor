package work.yoavtc.displayentitieseditor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;

/**
 * Makes moving a display look smooth for everyone watching: while a player is editing a display its position
 * interpolation duration (teleport_duration) is raised to a few ticks, and the display's own value is restored once
 * no player has edited it for {@link #IDLE_MS} (or the editor disconnects / the server stops).
 */
public final class EditSmoothing {
	/** Position interpolation used while an edit is in progress, in ticks. */
	public static final int EDIT_DURATION = 3;
	static final long IDLE_MS = 300;

	private static final class State {
		final Display display;
		final int original;
		final Map<UUID, Long> editors = new HashMap<>();

		State(Display display, int original) {
			this.display = display;
			this.original = original;
		}
	}

	private static final Map<UUID, State> ACTIVE = new HashMap<>();

	private EditSmoothing() {
	}

	/** Called whenever {@code player} edits {@code display}. */
	public static void touch(ServerPlayer player, Display display) {
		State s = ACTIVE.get(display.getUUID());
		if (s == null) {
			s = new State(display, display.getPosRotInterpolationDuration());
			ACTIVE.put(display.getUUID(), s);
		}
		s.editors.put(player.getUUID(), System.currentTimeMillis());
		if (display.getPosRotInterpolationDuration() != EDIT_DURATION) {
			display.setPosRotInterpolationDuration(EDIT_DURATION);
		}
	}

	/** Restores displays nobody has edited recently. */
	public static void tick() {
		if (ACTIVE.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		for (Iterator<State> it = ACTIVE.values().iterator(); it.hasNext();) {
			State s = it.next();
			s.editors.values().removeIf(t -> now - t > IDLE_MS);
			if (s.editors.isEmpty()) {
				restore(s);
				it.remove();
			}
		}
	}

	public static void playerLeft(UUID player) {
		for (Iterator<State> it = ACTIVE.values().iterator(); it.hasNext();) {
			State s = it.next();
			s.editors.remove(player);
			if (s.editors.isEmpty()) {
				restore(s);
				it.remove();
			}
		}
	}

	public static void restoreAll() {
		ACTIVE.values().forEach(EditSmoothing::restore);
		ACTIVE.clear();
	}

	private static void restore(State s) {
		// Only undo our own change; leave the value alone if something else (a command) changed it meanwhile.
		if (!s.display.isRemoved() && s.display.getPosRotInterpolationDuration() == EDIT_DURATION) {
			s.display.setPosRotInterpolationDuration(s.original);
		}
	}

	/** Test hook: whether a display is currently in smoothing mode. */
	public static boolean isActive(Display display) {
		return ACTIVE.containsKey(display.getUUID());
	}
}
