package work.yoavtc.displayentitieseditor.client;

import work.yoavtc.displayentitieseditor.net.CreateDisplayPayload.Kind;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload.Action;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;

/** Sneak + click on a display opens this: delete, change content, reset scale/rotation, align to block. */
public class QuickMenuScreen extends Screen {
	private final int entityId;
	private final Kind kind;
	private final boolean playerHead;

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

	private Button button(String label, int y, Runnable run) {
		return Button.builder(Component.literal(label), b -> run.run()).bounds((width - 180) / 2, y, 180, 20).build();
	}

	@Override
	protected void init() {
		int y = height / 2 - (playerHead ? 92 : 80);
		java.util.function.Supplier<Screen> back = () -> {
			var level = minecraft.level;
			return level != null && level.getEntity(entityId) instanceof Display d ? new QuickMenuScreen(d) : null;
		};
		switch (kind) {
			case BLOCK -> addRenderableWidget(button("Change Block", y, () -> minecraft.setScreenAndShow(
					new BrowserScreen(Kind.BLOCK, id -> send(Action.SET_BLOCK, id), back))));
			case ITEM -> addRenderableWidget(button("Change Item", y, () -> minecraft.setScreenAndShow(
					new BrowserScreen(Kind.ITEM, id -> send(Action.SET_ITEM, id), back))));
			case TEXT -> addRenderableWidget(button("Change Text", y, () -> minecraft.setScreenAndShow(
					new ContentScreen(Kind.TEXT, text -> send(Action.SET_TEXT, text), back))));
		}
		int row = 24;
		if (playerHead) {
			addRenderableWidget(button("Set Texture", y + row, () -> minecraft.setScreenAndShow(
					new ContentScreen("Head texture (Base64 value or textures.minecraft.net URL)", "eyJ0ZXh0dXJlcyI6...", QuickActionPayload.MAX_ARG,
							value -> send(Action.SET_TEXTURE, value), back))));
			row += 24;
		}
		addRenderableWidget(button("Reset Scale", y + row, () -> send(Action.RESET_SCALE)));
		addRenderableWidget(button("Reset Rotation", y + row + 24, () -> send(Action.RESET_ROTATION)));
		addRenderableWidget(button("Align Position to Block", y + row + 48, () -> send(Action.ALIGN_TO_BLOCK)));
		addRenderableWidget(button("Delete", y + row + 72, () -> {
			if (GizmoController.selected != null && GizmoController.selected.getId() == entityId) {
				GizmoController.selected = null;
			}
			send(Action.DELETE);
		}));
		addRenderableWidget(button("Close", y + row + 100, this::onClose));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(font, title, (width - font.width(title)) / 2, height / 2 - (playerHead ? 92 : 80) - 14, 0xFFFFFFFF, true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
