package work.yoavtc.displayentitieseditor.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.net.URI;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import work.yoavtc.displayentitieseditor.TextComponents;
import work.yoavtc.displayentitieseditor.net.TextEditPayload;
import work.yoavtc.displayentitieseditor.net.TextOptions;

/**
 * Create / edit screen for text displays: the text itself (plain or tellraw-style JSON) and every option
 * (background on/off, background colour as RGB and its opacity, shadow, alignment, billboard, text opacity).
 * When editing, every field starts with the display's current value.
 */
public class TextEditScreen extends Screen {
	public interface Submit {
		void accept(String text, TextOptions options);
	}

	private static final URI JSON_GENERATOR = URI.create("https://www.gamergeeks.net/apps/minecraft/raw-json-text-format-generator");

	/** A 0-255 slider that shows its value as a percentage. */
	private static final class PercentSlider extends AbstractSliderButton {
		private final String label;
		private final IntConsumer onChange;

		PercentSlider(int x, int y, int width, String label, int initial, IntConsumer onChange) {
			super(x, y, width, 20, Component.empty(), initial / 255.0);
			this.label = label;
			this.onChange = onChange;
			updateMessage();
		}

		private int current() {
			return (int) Math.round(value * 255);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label + ": " + Math.round(value * 100) + "%"));
		}

		@Override
		protected void applyValue() {
			onChange.accept(current());
		}
	}

	private final Submit submit;
	private final Supplier<Screen> back;

	// current values (kept here so they survive the screen being rebuilt, e.g. after the link confirmation)
	private String textValue = "";
	private int opacity = 255;
	private int backgroundAlpha = TextOptions.DEFAULT_BACKGROUND >>> 24;
	private final String[] rgb = {"0", "0", "0"};
	private boolean background = true;
	private boolean shadow = false;
	private int alignment = 0;
	private int billboard = 0;

	private EditBox text;
	private int top;

	/** @param existing the display being edited, or null when creating a new one with the default options */
	public TextEditScreen(Display.TextDisplay existing, Submit submit, Supplier<Screen> back) {
		super(Component.literal(existing == null ? "New text display" : "Edit text display"));
		this.submit = submit;
		this.back = back;
		if (existing != null) {
			readCurrent(existing);
		}
	}

	private void readCurrent(Display.TextDisplay d) {
		var minecraft = net.minecraft.client.Minecraft.getInstance();
		if (minecraft.level != null) {
			textValue = TextComponents.toEditable(d.getText(), minecraft.level.registryAccess());
		}
		byte flags = d.getFlags();
		boolean defaultBackground = (flags & Display.TextDisplay.FLAG_USE_DEFAULT_BACKGROUND) != 0;
		int bg = defaultBackground ? TextOptions.DEFAULT_BACKGROUND : d.getBackgroundColor();
		background = (bg >>> 24) != 0;
		backgroundAlpha = (bg >>> 24) != 0 ? bg >>> 24 : TextOptions.DEFAULT_BACKGROUND >>> 24;
		rgb[0] = String.valueOf((bg >> 16) & 0xFF);
		rgb[1] = String.valueOf((bg >> 8) & 0xFF);
		rgb[2] = String.valueOf(bg & 0xFF);
		shadow = (flags & Display.TextDisplay.FLAG_SHADOW) != 0;
		alignment = (flags & Display.TextDisplay.FLAG_ALIGN_LEFT) != 0 ? 1 : (flags & Display.TextDisplay.FLAG_ALIGN_RIGHT) != 0 ? 2 : 0;
		billboard = d.getBillboardConstraints().ordinal();
		opacity = d.getTextOpacity() & 0xFF;
	}

	private static String capitalized(String s) {
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String onOff(boolean value) {
		return value ? "On" : "Off";
	}

	@Override
	protected void init() {
		int w = Math.min(320, width - 20);
		int half = (w - 4) / 2;
		int x = (width - w) / 2;
		top = Math.max(16, (height - 184) / 2);

		text = new EditBox(font, x, top + 12, w, 20, title);
		text.setMaxLength(TextEditPayload.MAX_TEXT);
		text.setHint(Component.literal("Text or {\"text\":\"Hi\",\"color\":\"gold\"}"));
		text.setValue(textValue);
		text.setResponder(value -> textValue = value);
		addRenderableWidget(text);

		addRenderableWidget(Button.builder(Component.literal("Background: " + onOff(background)), b -> {
			background = !background;
			b.setMessage(Component.literal("Background: " + onOff(background)));
		}).bounds(x, top + 38, half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Shadow: " + onOff(shadow)), b -> {
			shadow = !shadow;
			b.setMessage(Component.literal("Shadow: " + onOff(shadow)));
		}).bounds(x + half + 4, top + 38, half, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Alignment: " + capitalized(TextOptions.ALIGNMENTS[alignment])), b -> {
			alignment = (alignment + 1) % TextOptions.ALIGNMENTS.length;
			b.setMessage(Component.literal("Alignment: " + capitalized(TextOptions.ALIGNMENTS[alignment])));
		}).bounds(x, top + 62, half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Billboard: " + capitalized(TextOptions.BILLBOARDS[billboard])), b -> {
			billboard = (billboard + 1) % TextOptions.BILLBOARDS.length;
			b.setMessage(Component.literal("Billboard: " + capitalized(TextOptions.BILLBOARDS[billboard])));
		}).bounds(x + half + 4, top + 62, half, 20).build());

		addRenderableWidget(new PercentSlider(x, top + 86, half, "Text opacity", opacity, v -> opacity = v));
		addRenderableWidget(new PercentSlider(x + half + 4, top + 86, half, "Background opacity", backgroundAlpha, v -> backgroundAlpha = v));

		// background colour as three 0-255 fields (R, G, B)
		int third = (w - 8) / 3;
		for (int i = 0; i < 3; i++) {
			final int channel = i;
			EditBox box = new EditBox(font, x + i * (third + 4), top + 120, third, 20, Component.literal(String.valueOf("RGB".charAt(i))));
			box.setMaxLength(3);
			box.setHint(Component.literal(String.valueOf("RGB".charAt(i))));
			box.setValue(rgb[i]);
			box.setResponder(value -> rgb[channel] = value);
			addRenderableWidget(box);
		}

		addRenderableWidget(Button.builder(Component.literal("Confirm"), b -> confirm()).bounds(x, top + 148, half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.setScreenAndShow(back.get()))
				.bounds(x + half + 4, top + 148, half, 20).build());

		// small link button in the top-right corner
		Button generator = Button.builder(Component.literal("JSON..."), b -> ConfirmLinkScreen.confirmLinkNow(this, JSON_GENERATOR))
				.bounds(width - 56, 4, 52, 16).build();
		generator.setTooltip(Tooltip.create(Component.literal("Open the raw JSON text generator in your browser")));
		addRenderableWidget(generator);

		setInitialFocus(text);
	}

	private static int channel(String value) {
		try {
			return Math.max(0, Math.min(255, Integer.parseInt(value.trim())));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private TextOptions options() {
		int color = (channel(rgb[0]) << 16) | (channel(rgb[1]) << 8) | channel(rgb[2]);
		return new TextOptions(background, color, backgroundAlpha, shadow, alignment, billboard, opacity);
	}

	private void confirm() {
		String value = textValue.strip();
		if (!value.isEmpty()) {
			submit.accept(value, options());
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
		int w = Math.min(320, width - 20);
		int x = (width - w) / 2;
		g.text(font, title, x, top, 0xFFFFFFFF, true);
		g.text(font, "Background colour (RGB, 0-255 each)", x, top + 110, 0xFFAAAAAA, true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
