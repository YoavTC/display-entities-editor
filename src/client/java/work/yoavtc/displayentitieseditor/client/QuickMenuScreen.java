package work.yoavtc.displayentitieseditor.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import work.yoavtc.displayentitieseditor.net.CreateDisplayPayload.Kind;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload.Action;
import work.yoavtc.displayentitieseditor.net.TextEditPayload;

/** Sneak + click on a display opens this: change content, billboard, reset scale/rotation, align to block, delete. */
public class QuickMenuScreen extends Screen {
	private final int entityId;
	private final Kind kind;
	private final boolean playerHead;
	private int titleY;

	public QuickMenuScreen(Display display) {
		super(Component.literal("Display actions"));
		this.entityId = display.getId();
		this.playerHead = display instanceof Display.ItemDisplay item && item.getItemStack().is(net.minecraft.world.item.Items.PLAYER_HEAD);
		this.kind = display instanceof Display.BlockDisplay ? Kind.BLOCK
				: display instanceof Display.ItemDisplay ? Kind.ITEM : Kind.TEXT;
	}

	private void send(Action action) {
		ClientPlayNetworking.send(new QuickActionPayload(entityId, action));
		onClose();
	}

	private void send(Action action, String arg) {
		ClientPlayNetworking.send(new QuickActionPayload(entityId, action, arg));
		onClose();
	}

	private record Row(String label, Runnable run) {
	}

	@Override
	protected void init() {
		Supplier<Screen> back = () -> {
			var level = minecraft.level;
			return level != null && level.getEntity(entityId) instanceof Display d ? new QuickMenuScreen(d) : null;
		};
		List<Row> rows = new ArrayList<>();
		switch (kind) {
			case BLOCK -> rows.add(new Row("Change Block", () -> minecraft.setScreenAndShow(
					new BrowserScreen(Kind.BLOCK, id -> send(Action.SET_BLOCK, id), back))));
			case ITEM -> rows.add(new Row("Change Item", () -> minecraft.setScreenAndShow(
					new BrowserScreen(Kind.ITEM, id -> send(Action.SET_ITEM, id), back))));
			case TEXT -> rows.add(new Row("Edit Text & Options", () -> {
				var level = minecraft.level;
				if (level != null && level.getEntity(entityId) instanceof Display.TextDisplay text) {
					minecraft.setScreenAndShow(new TextEditScreen(text, (value, options) -> {
						ClientPlayNetworking.send(new TextEditPayload(entityId, value, options));
						onClose();
					}, back));
				}
			}));
		}
		if (playerHead) {
			rows.add(new Row("Set Texture", () -> {
				String current = "";
				var level = minecraft.level;
				if (level != null && level.getEntity(entityId) instanceof Display.ItemDisplay item) {
					current = HeadTextures.currentValue(item.getItemStack(), level.registryAccess());
				}
				minecraft.setScreenAndShow(new ContentScreen("Head texture (Base64 value or textures.minecraft.net URL)",
						"eyJ0ZXh0dXJlcyI6...", QuickActionPayload.MAX_ARG, current, value -> send(Action.SET_TEXTURE, value), back));
			}));
		}
		if (kind != Kind.TEXT) {
			rows.add(new Row("Cycle Billboard", () -> send(Action.CYCLE_BILLBOARD)));
		}
		rows.add(new Row("Reset Scale", () -> send(Action.RESET_SCALE)));
		rows.add(new Row("Reset Rotation", () -> send(Action.RESET_ROTATION)));
		rows.add(new Row("Align Position to Block", () -> send(Action.ALIGN_TO_BLOCK)));
		rows.add(new Row("Delete", () -> {
			if (GizmoController.selected != null && GizmoController.selected.getId() == entityId) {
				GizmoController.selected = null;
			}
			send(Action.DELETE);
		}));

		int total = rows.size() * 24 + 28; // + gap and the Close button
		int y = Math.max(24, (height - total) / 2 + 6);
		titleY = y - 14;
		for (Row row : rows) {
			addRenderableWidget(Button.builder(Component.literal(row.label()), b -> row.run().run())
					.bounds((width - 180) / 2, y, 180, 20).build());
			y += 24;
		}
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
				.bounds((width - 180) / 2, y + 4, 180, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(font, title, (width - font.width(title)) / 2, titleY, 0xFFFFFFFF, true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
