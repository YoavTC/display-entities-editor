package work.yoavtc.displayentitieseditor;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.Transformation;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload;
import java.util.Locale;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Server side of the quick-action menu: delete, change content, reset scale/rotation, align to block. */
final class QuickActions {
	private QuickActions() {
	}

	static void register() {
		ServerPlayNetworking.registerGlobalReceiver(QuickActionPayload.TYPE, (payload, context) ->
				context.server().execute(() -> apply(context.player(), payload)));
	}

	private static void tell(ServerPlayer player, String message) {
		player.sendSystemMessage(Component.literal("[Display Entities Editor] " + message));
	}

	private static void apply(ServerPlayer player, QuickActionPayload p) {
		Display display = DisplayEntitiesEditor.findEditable(player, p.entityId());
		if (display == null) {
			return;
		}
		switch (p.action()) {
			case DELETE -> delete(player, display);
			case RESET_SCALE -> retransform(display, t -> new Transformation(new Vector3f(t.translation()),
					new Quaternionf(t.leftRotation()), new Vector3f(1, 1, 1), new Quaternionf(t.rightRotation())));
			case RESET_ROTATION -> {
				retransform(display, t -> new Transformation(new Vector3f(t.translation()), new Quaternionf(),
						new Vector3f(t.scale()), new Quaternionf()));
				display.setYRot(0);
				display.setXRot(0);
			}
			case ALIGN_TO_BLOCK -> {
				Vec3 c = center(display);
				Vec3 target = new Vec3(Math.floor(c.x) + 0.5, Math.floor(c.y) + 0.5, Math.floor(c.z) + 0.5);
				display.setPos(display.position().add(target.subtract(c)));
			}
			case SET_BLOCK -> {
				if (!(display instanceof Display.BlockDisplay)) {
					return;
				}
				Identifier id = FrameConversion.parseId(p.arg());
				if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
					tell(player, "Unknown block: " + p.arg());
					return;
				}
				String state = p.arg().contains(":") ? p.arg().trim() : "minecraft:" + p.arg().trim();
				merge(player, display, "block_state", "{block_state:" + StringTag.quoteAndEscape(state.toLowerCase(Locale.ROOT)) + "}");
			}
			case SET_ITEM -> {
				if (!(display instanceof Display.ItemDisplay)) {
					return;
				}
				Identifier id = FrameConversion.parseId(p.arg());
				if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
					tell(player, "Unknown item: " + p.arg());
					return;
				}
				merge(player, display, "item", "{item:{id:" + StringTag.quoteAndEscape(id.toString()) + ",count:1}}");
			}
			case SET_TEXTURE -> {
				if (!(display instanceof Display.ItemDisplay item) || !item.getItemStack().is(Items.PLAYER_HEAD)) {
					tell(player, "Set Texture only works on player head item displays.");
					return;
				}
				String value = textureValue(p.arg());
				if (value == null) {
					tell(player, "Invalid texture: use the Base64 texture value or a textures.minecraft.net URL.");
					return;
				}
				// item.components."minecraft:profile".properties[0].value
				merge(player, display, "item", "{item:{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":"
						+ "{properties:[{name:\"textures\",value:" + StringTag.quoteAndEscape(value) + "}]}}}}");
			}
			case SET_TEXT -> {
				if (!(display instanceof Display.TextDisplay) || p.arg().isBlank()) {
					return;
				}
				merge(player, display, "text", "{text:" + StringTag.quoteAndEscape(p.arg()) + "}");
			}
		}
	}

	/**
	 * Accepts a Base64 skin-texture value as found on head databases, or a textures.minecraft.net URL (which is
	 * wrapped into the usual {"textures":{"SKIN":{"url":...}}} JSON). Returns null if it is neither.
	 */
	static String textureValue(String raw) {
		String arg = raw.strip();
		if (arg.startsWith("http://") || arg.startsWith("https://")) {
			try {
				java.net.URI uri = java.net.URI.create(arg);
				if (!"textures.minecraft.net".equalsIgnoreCase(uri.getHost()) || !arg.matches("[A-Za-z0-9:/._-]+")) {
					return null;
				}
			} catch (IllegalArgumentException e) {
				return null;
			}
			String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + arg + "\"}}}";
			return java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		if (arg.isEmpty() || arg.length() > 1800 || !arg.matches("[A-Za-z0-9+/]+={0,2}")) {
			return null;
		}
		try {
			String json = new String(java.util.Base64.getDecoder().decode(arg), java.nio.charset.StandardCharsets.UTF_8);
			return json.contains("\"textures\"") ? arg : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static void delete(ServerPlayer player, Display display) {
		ServerLevel level = player.level();
		Vec3 at = center(display);
		boolean fromFrame = display.entityTags().contains(DisplayEntitiesEditor.FROM_FRAME_TAG);
		display.discard();
		if (!fromFrame) {
			return;
		}
		ItemEntity drop = new ItemEntity(level, at.x, at.y, at.z, new ItemStack(Items.ITEM_FRAME));
		drop.setDefaultPickUpDelay();
		level.addFreshEntity(drop);
	}

	/** Visual centre of the display in world space (block displays are drawn from their corner). */
	private static Vec3 center(Display display) {
		Transformation t = Display.createTransformation(display.getEntityData());
		float c = display instanceof Display.BlockDisplay ? 0.5f : 0f;
		Vector3f v = new Vector3f(c, c, c);
		t.getMatrixCopy().transformPosition(v);
		return display.position().add(v.x, v.y, v.z);
	}

	/** Applies a new transformation while keeping the visual centre where it was. */
	private static void retransform(Display display, java.util.function.UnaryOperator<Transformation> change) {
		Vec3 before = center(display);
		display.setTransformation(change.apply(Display.createTransformation(display.getEntityData())));
		Vec3 after = center(display);
		display.setPos(display.position().add(before.subtract(after)));
	}

	/** Like /data merge for one top-level key: save, replace that key, load back. */
	private static void merge(ServerPlayer player, Display display, String key, String snbt) {
		ServerLevel level = player.level();
		CompoundTag patch;
		try {
			patch = TagParser.parseCompoundFully(snbt);
		} catch (CommandSyntaxException e) {
			tell(player, "Invalid value.");
			return;
		}
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		display.saveWithoutId(out);
		CompoundTag tag = out.buildResult();
		tag.put(key, patch.get(key));
		display.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
	}
}
