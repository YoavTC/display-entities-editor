package work.yoavtc.displayentitieseditor.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Position readout, controls help panel and the tool button next to the hotbar. */
public final class GizmoHud {
	private static final int PANEL_BG = 0xC84A5262;
	private GizmoHud() {
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		if (!GizmoController.active) {
			return;
		}
		Font font = mc.font;
		drawHelp(g, font);
		String[] readout = GizmoController.readout();
		if (readout != null) {
			drawReadout(g, font, readout);
		} else {
			String hint = "Click a display entity to select it";
			int w = font.width(hint);
			int x = (g.guiWidth() - w) / 2;
			int y = (int) (g.guiHeight() * 0.785);
			g.fill(x - 2, y - 2, x + w + 2, y + 10, PANEL_BG);
			g.text(font, hint, x, y, 0xFFFFFFFF, true);
		}
	}

	private static void drawReadout(GuiGraphicsExtractor g, Font font, String[] r) {
		String sep = " ";
		int total = font.width(r[0]) + font.width(sep) * 3 + font.width(r[1]) + font.width(r[2]) + font.width(r[3]);
		int x = (g.guiWidth() - total) / 2;
		int y = (int) (g.guiHeight() * 0.785);
		g.fill(x - 2, y - 2, x + total + 2, y + 10, PANEL_BG);
		int[] colors = {0xFFFFFF55, 0xFFFF5555, 0xFF55FF55, 0xFF55FFFF};
		for (int i = 0; i < 4; i++) {
			g.text(font, r[i], x, y, colors[i], true);
			x += font.width(r[i]) + font.width(sep);
		}
	}

	private static String key(net.minecraft.client.KeyMapping mapping) {
		return mapping.getTranslatedKeyMessage().getString();
	}

	/** Help lines built from the player's current key bindings. */
	private static String[] helpLines(Minecraft mc) {
		return new String[] {
				"Mode: " + GizmoController.mode.label + " (" + key(DisplayEntitiesEditorClient.cycleModeKey) + " to cycle)",
				key(mc.options.keyUse) + " / " + key(mc.options.keyAttack) + " - Edit/Move",
				"Scroll - Nudge " + GizmoController.nudgeLabel() + " (" + key(DisplayEntitiesEditorClient.cycleNudgeKey) + " to change)",
				"Hold Ctrl - Inc Snapping",
				"Hold Shift - No Snapping",
				key(mc.options.keyShift) + " + Click - Quick Menu",
				key(DisplayEntitiesEditorClient.deleteKey) + " - Remove",
				"Ctrl+" + key(DisplayEntitiesEditorClient.undoKey) + " - Undo",
		};
	}

	private static void drawHelp(GuiGraphicsExtractor g, Font font) {
		int lineH = 9;
		String[] lines = helpLines(Minecraft.getInstance());
		int width = 0;
		for (String s : lines) {
			width = Math.max(width, font.width(s));
		}
		int right = g.guiWidth();
		int bottom = (int) (g.guiHeight() * 0.89);
		int top = bottom - lines.length * lineH - 1;
		g.fill(right - width - 3, top, right, bottom, PANEL_BG);
		for (int i = 0; i < lines.length; i++) {
			g.text(font, lines[i], right - font.width(lines[i]) - 1, top + 1 + i * lineH, 0xFFFFFFFF, true);
		}
	}
}
