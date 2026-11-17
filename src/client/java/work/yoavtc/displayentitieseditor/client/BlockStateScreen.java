package work.yoavtc.displayentitieseditor.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload.Action;

/**
 * Edits the block state of a block display: every property of its block is listed, with a button per allowed
 * value (the current one highlighted), or a slider for whole-number properties such as age or power.
 * Changes preview on the display right away; Apply sends them, Back / Esc undoes the preview.
 */
public class BlockStateScreen extends Screen {
	private static final int ROW = 24;
	private static final int LABEL = 12;
	private static final int GAP = 4;
	private static final int MIN_BUTTON = 30;

	/** A slider over a property's whole-number values, moving in steps of one. */
	private final class IntSlider extends AbstractSliderButton {
		private final IntegerProperty property;
		private final List<Integer> values;

		IntSlider(int x, int y, int width, IntegerProperty property) {
			super(x, y, width, 20, Component.empty(), 0);
			this.property = property;
			this.values = property.getPossibleValues();
			int index = values.indexOf(current.getValue(property));
			value = values.size() < 2 ? 0 : Math.max(0, index) / (double) (values.size() - 1);
			updateMessage();
		}

		private int selected() {
			return values.get((int) Math.round(value * (values.size() - 1)));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(property.getName() + ": " + selected()));
		}

		@Override
		protected void applyValue() {
			value = values.size() < 2 ? 0 : values.indexOf(selected()) / (double) (values.size() - 1); // snap to a whole step
			choose(property, selected());
		}
	}

	private record Label(String text, int y) {
	}

	private final Display.BlockDisplay display;
	private final Supplier<Screen> back;
	private final BlockState original;
	private BlockState current;
	private boolean applied;

	private final List<Label> labels = new ArrayList<>();
	private int scroll;
	private int contentHeight;
	private int viewTop;
	private int viewBottom;

	public BlockStateScreen(Display.BlockDisplay display, Supplier<Screen> back) {
		super(Component.literal("Block state: ").append(display.getBlockState().getBlock().getName()));
		this.display = display;
		this.back = back;
		this.original = display.getBlockState();
		this.current = original;
	}

	/** Whether the display's block has any state properties to edit. */
	public static boolean hasProperties(Display.BlockDisplay display) {
		return !display.getBlockState().getProperties().isEmpty();
	}

	private <T extends Comparable<T>> void choose(Property<T> property, T value) {
		if (current.getValue(property).equals(value)) {
			return;
		}
		current = current.setValue(property, value);
		// preview on the client only; the server decides when Apply is pressed
		if (!display.isRemoved()) {
			display.setBlockState(current);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> String valueName(Property<T> property, Object value) {
		return property.getName((T) value);
	}

	@Override
	protected void init() {
		int w = Math.min(340, width - 20);
		int x = (width - w) / 2;
		viewTop = 32;
		viewBottom = height - 36;
		labels.clear();
		scroll = Math.max(0, Math.min(scroll, maxScroll())); // the view may have shrunk (window resized)

		int y = 0;
		for (Property<?> property : current.getProperties()) {
			labels.add(new Label(property.getName(), y));
			y += LABEL;
			if (property instanceof IntegerProperty ints) {
				if (visible(y)) {
					addRenderableWidget(new IntSlider(x, viewTop + y - scroll, w, ints));
				}
				y += ROW;
			} else {
				y = addValueButtons(property, x, w, y);
			}
			y += GAP;
		}
		contentHeight = y;

		int half = (w - 4) / 2;
		addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply()).bounds(x, height - 28, half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.setScreenAndShow(back.get()))
				.bounds(x + half + 4, height - 28, half, 20).build());
	}

	/** One button per allowed value, wrapping onto more lines as needed. Returns the y below the last line. */
	private <T extends Comparable<T>> int addValueButtons(Property<T> property, int x, int w, int y) {
		int lineX = 0;
		for (T value : property.getPossibleValues()) {
			String name = valueName(property, value);
			int bw = Math.max(MIN_BUTTON, font.width(name) + 12);
			if (lineX > 0 && lineX + bw > w) {
				lineX = 0;
				y += ROW;
			}
			if (visible(y)) {
				boolean selected = current.getValue(property).equals(value);
				Component message = selected
						? Component.literal(name).withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE)
						: Component.literal(name);
				addRenderableWidget(Button.builder(message, b -> {
					choose(property, value);
					rebuildWidgets(); // move the highlight
				}).bounds(x + lineX, viewTop + y - scroll, bw, 20).build());
			}
			lineX += bw + GAP;
		}
		return y + ROW;
	}

	/** A 20 px high row at content offset {@code y} lies fully inside the scrolled view. */
	private boolean visible(int y) {
		int top = viewTop + y - scroll;
		return top >= viewTop && top + 20 <= viewBottom;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight - (viewBottom - viewTop));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (maxScroll() > 0 && scrollY != 0) {
			int next = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(scrollY) * ROW));
			if (next != scroll) {
				scroll = next;
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** Sends the properties that changed, as "name=value,name=value". */
	private void apply() {
		StringBuilder arg = new StringBuilder();
		for (Property<?> property : current.getProperties()) {
			Object now = current.getValue(property);
			if (!now.equals(original.getValue(property))) {
				if (!arg.isEmpty()) {
					arg.append(',');
				}
				arg.append(property.getName()).append('=').append(valueName(property, now));
			}
		}
		applied = true;
		if (!arg.isEmpty()) {
			ClientPlayNetworking.send(new QuickActionPayload(display.getId(), Action.SET_BLOCK_STATE, arg.toString()));
		}
		onClose();
	}

	@Override
	public void removed() {
		// left without applying: take the preview back
		if (!applied && !display.isRemoved() && display.getBlockState() == current) {
			display.setBlockState(original);
		}
		super.removed();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			apply();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		int w = Math.min(340, width - 20);
		int x = (width - w) / 2;
		g.text(font, title, (width - font.width(title)) / 2, 12, 0xFFFFFFFF, true);
		for (Label label : labels) {
			int top = viewTop + label.y() - scroll;
			if (top >= viewTop && top + LABEL <= viewBottom) {
				g.text(font, label.text(), x, top + 2, 0xFFAAAAAA, true);
			}
		}
		if (maxScroll() > 0) {
			String hint = "Scroll for more (" + (scroll * 100 / maxScroll()) + "%)";
			g.text(font, hint, x + w - font.width(hint), 22, 0xFF777777, true);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
