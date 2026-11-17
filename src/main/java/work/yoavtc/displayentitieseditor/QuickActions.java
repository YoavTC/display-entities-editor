package work.yoavtc.displayentitieseditor;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.Transformation;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload;
import work.yoavtc.displayentitieseditor.net.TextEditPayload;
import work.yoavtc.displayentitieseditor.net.TextOptions;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Server side of the quick-action menu: delete, change content or block state, reset scale/rotation, align to block. */
final class QuickActions {
	private QuickActions() {
	}

	static void register() {
		ServerPlayNetworking.registerGlobalReceiver(QuickActionPayload.TYPE, (payload, context) ->
				context.server().execute(() -> apply(context.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(TextEditPayload.TYPE, (payload, context) ->
				context.server().execute(() -> editText(context.player(), payload)));
	}

	private static void tell(ServerPlayer player, String message) {
		player.sendSystemMessage(Component.literal("[Display Entities Editor] " + message));
	}

	private static void apply(ServerPlayer player, QuickActionPayload p) {
		applyAction(player, p);
		if (p.action() == QuickActionPayload.Action.SET_BLOCK_STATE) {
			resyncBlockState(player, p.entityId());
		}
	}

	/**
	 * The block state screen previews changes on the client before sending them. Whatever happened to the request
	 * (applied, refused, invalid), send that player the display's real block state so a refused preview is undone.
	 */
	private static void resyncBlockState(ServerPlayer player, int entityId) {
		if (player.level().getEntity(entityId) instanceof Display.BlockDisplay block
				&& block.distanceToSqr(player) <= DisplayEntitiesEditor.MAX_PLAYER_DISTANCE * DisplayEntitiesEditor.MAX_PLAYER_DISTANCE) {
			player.connection.send(new ClientboundSetEntityDataPacket(entityId,
					List.of(SynchedEntityData.DataValue.create(Display.BlockDisplay.DATA_BLOCK_STATE_ID, block.getBlockState()))));
		}
	}

	private static void applyAction(ServerPlayer player, QuickActionPayload p) {
		Display display = DisplayEntitiesEditor.findEditable(player, p.entityId());
		if (display == null) {
			return;
		}
		int maxArg = p.action() == QuickActionPayload.Action.SET_TEXTURE ? QuickActionPayload.MAX_ARG : QuickActionPayload.SHORT_ARG;
		if (p.arg().length() > maxArg) {
			tell(player, "That value is too long.");
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
			case SET_BLOCK_STATE -> {
				if (!(display instanceof Display.BlockDisplay block)) {
					return;
				}
				BlockState state = withProperties(player, block.getBlockState(), p.arg());
				if (state != null) {
					block.setBlockState(state);
				}
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
				if (value != null && value.length() > MAX_PROFILE_VALUE) {
					// vanilla drops the whole profile component when a property value is longer than this
					tell(player, "That texture value is " + value.length() + " characters; Minecraft itself only allows up to "
							+ MAX_PROFILE_VALUE + ", so the head was left unchanged.");
					return;
				}
				if (value == null) {
					tell(player, "Invalid texture: use the Base64 texture value or a textures.minecraft.net URL.");
					return;
				}
				// item.components."minecraft:profile".properties[0].value
				merge(player, display, "item", "{item:{id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":"
						+ "{properties:[{name:\"textures\",value:" + StringTag.quoteAndEscape(value) + "}]}}}}");
			}
			case CYCLE_BILLBOARD -> modify(player, display, tag -> {
				String[] modes = TextOptions.BILLBOARDS;
				String current = tag.getStringOr("billboard", "fixed");
				int index = 0;
				for (int k = 0; k < modes.length; k++) {
					if (modes[k].equals(current)) {
						index = k;
					}
				}
				tag.putString("billboard", modes[(index + 1) % modes.length]);
			});
		}
	}

	/**
	 * Applies "name=value,name=value" to a block state. Only properties the block really has, with values it allows,
	 * are accepted (the block itself never changes). Returns null, after telling the player why, if anything is invalid.
	 */
	private static BlockState withProperties(ServerPlayer player, BlockState state, String arg) {
		for (String pair : arg.split(",")) {
			if (pair.isBlank()) {
				continue;
			}
			int eq = pair.indexOf('=');
			if (eq < 0) {
				tell(player, "Invalid block state: " + pair.strip());
				return null;
			}
			String name = pair.substring(0, eq).strip();
			String value = pair.substring(eq + 1).strip();
			Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
			if (property == null) {
				tell(player, "This block has no \"" + name + "\" property.");
				return null;
			}
			BlockState next = withValue(state, property, value);
			if (next == null) {
				tell(player, "\"" + value + "\" is not a valid value for " + name + ".");
				return null;
			}
			state = next;
		}
		return state;
	}

	private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(null);
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
		// no length limit: skin values can be very long. Validate the alphabet by hand (no regex on huge input).
		if (arg.isEmpty() || !isBase64(arg)) {
			return null;
		}
		try {
			String json = new String(java.util.Base64.getDecoder().decode(arg), java.nio.charset.StandardCharsets.UTF_8);
			return json.contains("\"textures\"") ? arg : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Minecraft's own limit for a profile property value (longer values make the item lose its profile). */
	static final int MAX_PROFILE_VALUE = 32767;

	private static boolean isBase64(String s) {
		int end = s.length();
		while (end > 0 && s.charAt(end - 1) == '=' && s.length() - end < 2) {
			end--;
		}
		for (int i = 0; i < end; i++) {
			char c = s.charAt(i);
			if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/')) {
				return false;
			}
		}
		return end > 0;
	}

	/** Applies a new text (plain or tellraw-style JSON) and all text options in one go. */
	private static void editText(ServerPlayer player, TextEditPayload p) {
		Display display = DisplayEntitiesEditor.findEditable(player, p.entityId());
		if (display == null || !(display instanceof Display.TextDisplay)) {
			return;
		}
		TextComponents.Result text = TextComponents.parse(p.text(), player.level().registryAccess());
		if (text.tag() == null) {
			tell(player, text.error());
			return;
		}
		modify(player, display, tag -> {
			tag.put("text", text.tag());
			p.options().applyTo(tag);
		});
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

	/** Save the display to NBT, let {@code change} edit it, and load it back (what /data modify does). */
	private static void modify(ServerPlayer player, Display display, java.util.function.Consumer<CompoundTag> change) {
		ServerLevel level = player.level();
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		display.saveWithoutId(out);
		CompoundTag tag = out.buildResult();
		change.accept(tag);
		display.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
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
