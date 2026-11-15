package work.yoavtc.displayentitieseditor.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.net.URI;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
 * (background, background colour, shadow, alignment, billboard, text opacity). When editing, every field starts
 * with the display's current value.
 */
public class TextEditScreen extends Screen {
	public interface Submit {
		void accept(String text, TextOptions options);
	}

	private static final URI JSON_GENERATOR = URI.create("https://www.gamergeeks.net/apps/minecraft/raw-json-text-format-generator");

	private final Submit submit;
	private final Supplier<Screen> back;

	// current values (kept here so they survive the screen being rebuilt, e.g. after the link confirmation)
	private String textValue = "";
	private String opacityValue = "255";
	private String rgbValue = "000000";
	private boolean background = true;
	private boolean shadow = false;
	private int alignment = 0;
	private int billboard = 0;

	private EditBox text;
	private EditBox opacity;
	private EditBox rgb;
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
		int bg = d.getBackgroundColor();
		boolean defaultBackground = (flags & Display.TextDisplay.FLAG_USE_DEFAULT_BACKGROUND) != 0;
		background = defaultBackground || (bg >>> 24) != 0;
		rgbValue = String.format(Locale.ROOT, "%06X", (defaultBackground ? TextOptions.DEFAULT_BACKGROUND : bg) & 0xFFFFFF);
		shadow = (flags & Display.TextDisplay.FLAG_SHADOW) != 0;
		alignment = (flags & Display.TextDisplay.FLAG_ALIGN_LEFT) != 0 ? 1 : (flags & Display.TextDisplay.FLAG_ALIGN_RIGHT) != 0 ? 2 : 0;
		billboard = d.getBillboardConstraints().ordinal();
		opacityValue = String.valueOf(d.getTextOpacity() & 0xFF);
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
		top = Math.max(16, (height - 190) / 2);

		text = new EditBox(font, x, top + 12, w, 20, title);
		text.setMaxLength(TextEditPayload.MAX_TEXT);
		text.setHint(Component.literal("Text or {\"text\":\"Hi\",\"color\":\"gold\"}"));
		text.setValue(textValue);
		text.setResponder(value -> textValue = value);
		addRenderableWidget(text);

		Button backgroundButton = Button.builder(Component.literal("Background: " + onOff(background)), b -> {
			background = !background;
			b.setMessage(Component.literal("Background: " + onOff(background)));
		}).bounds(x, top + 38, half, 20).build();
		addRenderableWidget(backgroundButton);
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

		opacity = new EditBox(font, x, top + 98, half, 20, Component.literal("Text opacity"));
		opacity.setMaxLength(3);
		opacity.setValue(opacityValue);
		opacity.setResponder(value -> opacityValue = value);
		addRenderableWidget(opacity);
		rgb = new EditBox(font, x + half + 4, top + 98, half, 20, Component.literal("Background RGB"));
		rgb.setMaxLength(7);
		rgb.setValue(rgbValue);
		rgb.setResponder(value -> rgbValue = value);
		addRenderableWidget(rgb);

		addRenderableWidget(Button.builder(Component.literal("Open JSON Text Generator..."),
				b -> ConfirmLinkScreen.confirmLinkNow(this, JSON_GENERATOR)).bounds(x, top + 124, w, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Confirm"), b -> confirm()).bounds(x, top + 152, half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.setScreenAndShow(back.get()))
				.bounds(x + half + 4, top + 152, half, 20).build());
		setInitialFocus(text);
	}

	private TextOptions options() {
		int alpha;
		try {
			alpha = Integer.parseInt(opacityValue.trim());
		} catch (NumberFormatException e) {
			alpha = 255;
		}
		int color;
		try {
			String hex = rgbValue.trim();
			color = Integer.parseInt(hex.startsWith("#") ? hex.substring(1) : hex, 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			color = 0;
		}
		return new TextOptions(background, color, shadow, alignment, billboard, Math.max(0, Math.min(255, alpha)));
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
		int half = (w - 4) / 2;
		int x = (width - w) / 2;
		g.text(font, title, (width - font.width(title)) / 2, top, 0xFFFFFFFF, true);
		g.text(font, "Text opacity (0-255)", x, top + 87, 0xFFAAAAAA, true);
		g.text(font, "Background RGB (hex)", x + half + 4, top + 87, 0xFFAAAAAA, true);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
