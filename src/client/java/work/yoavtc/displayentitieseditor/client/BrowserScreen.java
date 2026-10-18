package work.yoavtc.displayentitieseditor.client;

import work.yoavtc.displayentitieseditor.net.CreateDisplayPayload.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/** Searchable, scrollable grid of every block or item; clicking one creates the display. */
public class BrowserScreen extends Screen {
	private record Entry(ItemStack stack, String id, String searchText) {
	}

	private static final int CELL = 20;
	private static final int COLUMNS = 12;
	private static List<Entry> blockEntries;
	private static List<Entry> itemEntries;

	private final Kind kind;
	private final Consumer<String> onPick;
	private final Supplier<Screen> back;
	private final List<Entry> all;
	private List<Entry> shown;
	private EditBox search;
	private int scrollRows;
	private int gridX;
	private int gridY;
	private int visibleRows;

	public BrowserScreen(Kind kind, Consumer<String> onPick, Supplier<Screen> back) {
		super(Component.literal(kind == Kind.BLOCK ? "Choose a block" : "Choose an item"));
		this.onPick = onPick;
		this.back = back;
		this.kind = kind;
		this.all = entries(kind);
		this.shown = all;
	}

	private static List<Entry> entries(Kind kind) {
		if (kind == Kind.BLOCK) {
			if (blockEntries == null) {
				List<Entry> list = new ArrayList<>();
				for (Block block : BuiltInRegistries.BLOCK) {
					Item item = block.asItem();
					if (item != Items.AIR) {
						list.add(make(new ItemStack(item), BuiltInRegistries.BLOCK.getKey(block).toString()));
					}
				}
				blockEntries = list;
			}
			return blockEntries;
		}
		if (itemEntries == null) {
			List<Entry> list = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item != Items.AIR) {
					list.add(make(new ItemStack(item), BuiltInRegistries.ITEM.getKey(item).toString()));
				}
			}
			itemEntries = list;
		}
		return itemEntries;
	}

	private static Entry make(ItemStack stack, String id) {
		return new Entry(stack, id, (id + " " + stack.getHoverName().getString()).toLowerCase(Locale.ROOT));
	}

	@Override
	protected void init() {
		int gridW = COLUMNS * CELL;
		gridX = (width - gridW) / 2;
		search = new EditBox(font, gridX, 26, gridW, 18, Component.literal("Search"));
		search.setHint(Component.literal("Search..."));
		search.setResponder(this::filter);
		addRenderableWidget(search);
		setInitialFocus(search);
		gridY = 50;
		visibleRows = Math.max(1, (height - gridY - 34) / CELL);
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.setScreenAndShow(back.get()))
				.bounds(width / 2 - 50, height - 26, 100, 20).build());
	}

	private void filter(String text) {
		String q = text.strip().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			shown = all;
		} else {
			List<Entry> out = new ArrayList<>();
			for (Entry e : all) {
				if (e.searchText.contains(q)) {
					out.add(e);
				}
			}
			shown = out;
		}
		scrollRows = 0;
	}

	private int totalRows() {
		return (shown.size() + COLUMNS - 1) / COLUMNS;
	}

	private int entryAt(double mx, double my) {
		int col = (int) Math.floor((mx - gridX) / CELL);
		int row = (int) Math.floor((my - gridY) / CELL);
		if (mx < gridX || my < gridY || col < 0 || col >= COLUMNS || row < 0 || row >= visibleRows) {
			return -1;
		}
		int index = (row + scrollRows) * COLUMNS + col;
		return index < shown.size() ? index : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// In this version the left mouse button is reported as 1 (see AbstractWidget.isValidClickButton).
		if (event.button() == 1) {
			int index = entryAt(event.x(), event.y());
			if (index >= 0) {
				onPick.accept(shown.get(index).id);
				onClose();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int max = Math.max(0, totalRows() - visibleRows);
		scrollRows = Math.max(0, Math.min(max, scrollRows - (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(font, title, (width - font.width(title)) / 2, 10, 0xFFFFFFFF, true);
		g.fill(gridX - 2, gridY - 2, gridX + COLUMNS * CELL + 2, gridY + visibleRows * CELL + 2, 0xB0000000);
		int hovered = entryAt(mouseX, mouseY);
		for (int row = 0; row < visibleRows; row++) {
			for (int col = 0; col < COLUMNS; col++) {
				int index = (row + scrollRows) * COLUMNS + col;
				if (index >= shown.size()) {
					break;
				}
				int x = gridX + col * CELL;
				int y = gridY + row * CELL;
				if (index == hovered) {
					g.fill(x, y, x + CELL, y + CELL, 0x80FFFFFF);
				}
				g.item(shown.get(index).stack, x + 2, y + 2);
			}
		}
		if (shown.isEmpty()) {
			g.text(font, "No matches", gridX + 4, gridY + 4, 0xFFAAAAAA, true);
		}
		if (hovered >= 0) {
			Entry e = shown.get(hovered);
			g.setComponentTooltipForNextFrame(font, List.of(e.stack.getHoverName(), Component.literal(e.id)), mouseX, mouseY);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
