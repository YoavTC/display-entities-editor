package work.yoavtc.displayentitieseditor.client;

import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload.Action;

/** Toggles for the text display settings that are not part of the text itself (background, shadow, alignment...). */
public class TextOptionsScreen extends Screen {
	private final int entityId;
	private final Supplier<Screen> back;

	public TextOptionsScreen(int entityId, Supplier<Screen> back) {
		super(Component.literal("Text options"));
		this.entityId = entityId;
		this.back = back;
	}

	private Button button(String label, int y, Action action) {
		return Button.builder(Component.literal(label), b -> ClientPlayNetworking.send(new QuickActionPayload(entityId, action)))
				.bounds((width - 180) / 2, y, 180, 20).build();
	}

	@Override
	protected void init() {
		int y = height / 2 - 56;
		addRenderableWidget(button("Toggle Background", y, Action.TOGGLE_BACKGROUND));
		addRenderableWidget(button("Toggle Shadow", y + 24, Action.TOGGLE_SHADOW));
		addRenderableWidget(button("Toggle See-Through", y + 48, Action.TOGGLE_SEE_THROUGH));
		addRenderableWidget(button("Cycle Alignment", y + 72, Action.CYCLE_ALIGNMENT));
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
			Screen previous = back.get();
			minecraft.setScreenAndShow(previous);
		}).bounds((width - 180) / 2, y + 104, 180, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(font, title, (width - font.width(title)) / 2, height / 2 - 72, 0xFFFFFFFF, true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
