package work.yoavtc.displayentitieseditor.client;

import work.yoavtc.displayentitieseditor.net.CreateDisplayPayload.Kind;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** First prompt after using the wand on an item frame: which kind of display should it become? */
public class ChooseTypeScreen extends Screen {
	private final int frameId;

	public ChooseTypeScreen(int frameId) {
		super(Component.literal("Convert item frame to..."));
		this.frameId = frameId;
	}

	@Override
	protected void init() {
		int w = 160;
		int x = (width - w) / 2;
		int y = height / 2 - 30;
		addRenderableWidget(Button.builder(Component.literal("Text Display"), b -> open(Kind.TEXT)).bounds(x, y, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Block Display"), b -> open(Kind.BLOCK)).bounds(x, y + 24, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Item Display"), b -> open(Kind.ITEM)).bounds(x, y + 48, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x, y + 76, w, 20).build());
	}

	private void open(Kind kind) {
		java.util.function.Consumer<String> create = value ->
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new work.yoavtc.displayentitieseditor.net.CreateDisplayPayload(frameId, kind, value));
		java.util.function.Supplier<Screen> back = () -> new ChooseTypeScreen(frameId);
		minecraft.setScreenAndShow(kind == Kind.TEXT ? new ContentScreen(kind, create, back) : new BrowserScreen(kind, create, back));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(font, title, (width - font.width(title)) / 2, height / 2 - 48, 0xFFFFFFFF, true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
