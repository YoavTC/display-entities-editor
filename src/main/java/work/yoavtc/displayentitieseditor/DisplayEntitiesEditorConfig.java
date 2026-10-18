package work.yoavtc.displayentitieseditor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Tiny JSON config (config/display_entities_editor.json). Both client and server read their own copy. */
public final class DisplayEntitiesEditorConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static String wandItem = "minecraft:breeze_rod";
	private static boolean allowNonOperators = true;
	/** Base width of the lines drawn by the in-world editor, in pixels. */
	public static final float DEFAULT_LINE_WIDTH = 1.5f;
	private static float lineWidth = DEFAULT_LINE_WIDTH;

	private DisplayEntitiesEditorConfig() {
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("display_entities_editor.json");
		try {
			if (Files.exists(path)) {
				JsonObject json = GSON.fromJson(Files.readString(path), JsonObject.class);
				if (json != null && json.has("wand_item")) {
					wandItem = normalize(json.get("wand_item").getAsString());
				}
				if (json != null && json.has("allow_non_operators")) {
					allowNonOperators = json.get("allow_non_operators").getAsBoolean();
				}
				if (json != null && json.has("line_width")) {
					lineWidth = Math.max(0.5f, Math.min(10f, json.get("line_width").getAsFloat()));
				}
				if (json == null || !json.has("wand_item") || !json.has("allow_non_operators") || !json.has("line_width")) {
					write(path); // add any newly introduced options to an older file
				}
			} else {
				write(path);
			}
		} catch (IOException | RuntimeException e) {
			DisplayEntitiesEditor.LOGGER.warn("Could not read {}, using defaults (wand {}, non-operators {})", path, wandItem,
					allowNonOperators, e);
		}
	}

	private static void write(Path path) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("wand_item", wandItem);
		json.addProperty("allow_non_operators", allowNonOperators);
		json.addProperty("line_width", lineWidth);
		Files.writeString(path, GSON.toJson(json));
	}

	private static String normalize(String id) {
		id = id.trim().toLowerCase(java.util.Locale.ROOT);
		return id.contains(":") ? id : "minecraft:" + id;
	}

	/** Whether players without operator permission may edit displays (server side setting, default true). */
	public static boolean allowNonOperators() {
		return allowNonOperators;
	}

	/** Test hook: the game tests flip this at runtime. */
	public static void setAllowNonOperators(boolean allow) {
		allowNonOperators = allow;
	}

	/** Base line width for the in-world editor (client side setting, default 1.5, clamped to 0.5 - 10). */
	public static float lineWidth() {
		return lineWidth;
	}

	/** Test hook. */
	public static void setLineWidth(float width) {
		lineWidth = Math.max(0.5f, Math.min(10f, width));
	}

	public static String wandItem() {
		return wandItem;
	}

	public static boolean isWand(ItemStack stack) {
		return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(wandItem);
	}
}
