package work.yoavtc.displayentitieseditor.client;

import com.mojang.blaze3d.platform.InputConstants;
import work.yoavtc.displayentitieseditor.net.CreateDisplayPayload.Kind;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Second prompt: what text / block / item should the new display show? */
public class ContentScreen extends Screen {
	private final String hint;
	private final Consumer<String> onConfirm;
	private final Supplier<Screen> back;
	private EditBox input;

	public ContentScreen(Kind kind, Consumer<String> onConfirm, Supplier<Screen> back) {
		this(switch (kind) {
			case TEXT -> "Text to display";
			case BLOCK -> "Block to display (e.g. minecraft:tnt)";
			case ITEM -> "Item to display (e.g. minecraft:diamond)";
		}, switch (kind) {
			case TEXT -> "Hello world";
			case BLOCK -> "minecraft:tnt";
			case ITEM -> "minecraft:diamond";
		}, onConfirm, back);
	}

	public ContentScreen(String title, String hint, Consumer<String> onConfirm, Supplier<Screen> back) {
		super(Component.literal(title));
		this.hint = hint;
		this.onConfirm = onConfirm;
		this.back = back;
	}

	@Override
	protected void init() {
		int w = 240;
		int x = (width - w) / 2;
		int y = height / 2 - 20;
		input = new EditBox(font, x, y, w, 20, title);
		input.setMaxLength(work.yoavtc.displayentitieseditor.net.QuickActionPayload.MAX_ARG);
		input.setHint(Component.literal(hint));
		addRenderableWidget(input);
		addRenderableWidget(Button.builder(Component.literal("Confirm"), b -> confirm()).bounds(x, y + 28, w / 2 - 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.setScreenAndShow(back.get()))
				.bounds(x + w / 2 + 2, y + 28, w / 2 - 2, 20).build());
		setInitialFocus(input);
	}

	private void confirm() {
		String value = input.getValue().strip();
		if (!value.isEmpty()) {
			onConfirm.accept(value);
			onClose();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(font, title, (width - font.width(title)) / 2, height / 2 - 36, 0xFFFFFFFF, true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
