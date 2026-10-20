package work.yoavtc.displayentitieseditor;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import work.yoavtc.displayentitieseditor.net.CreateDisplayPayload;
import work.yoavtc.displayentitieseditor.net.OpenMenuPayload;
import work.yoavtc.displayentitieseditor.net.SelectPayload;
import java.util.Locale;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/** Using the wand on an empty (glow) item frame turns it into a text, block or item display. */
final class FrameConversion {
	private static final double MAX_DISTANCE_SQR = 12.0 * 12.0;

	private FrameConversion() {
	}

	static void register() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp) || !(entity instanceof ItemFrame frame)
					|| !DisplayEntitiesEditorConfig.isWand(player.getItemInHand(hand)) || !frame.getItem().isEmpty()) {
				return InteractionResult.PASS;
			}
			if (DisplayEntitiesEditor.checkPermission(sp)) {
				ServerPlayNetworking.send(sp, new OpenMenuPayload(frame.getId()));
			}
			return InteractionResult.SUCCESS;
		});

		ServerPlayNetworking.registerGlobalReceiver(CreateDisplayPayload.TYPE, (payload, context) ->
				context.server().execute(() -> create(context.player(), payload)));
	}

	private static void tell(ServerPlayer player, String message) {
		player.sendSystemMessage(Component.literal("[Display Entities Editor] " + message));
	}

	/**
	 * Pos/Rotation for a flat display (text, item). A frame on the floor or ceiling has a +-90 degree pitch
	 * that would leave the display lying flat, so that 90 degree offset around X is dropped and the display
	 * stands upright, nudged {@code lift} blocks off the surface.
	 */
	private static String uprightHead(ItemFrame frame, Vec3 pos, double lift) {
		boolean horizontal = Math.abs(frame.getXRot()) > 45;
		float pitch = horizontal ? 0f : frame.getXRot();
		double y = horizontal ? pos.y + (frame.getXRot() < 0 ? lift : -lift) : pos.y;
		return "{Pos:[" + pos.x + "d," + y + "d," + pos.z + "d],Rotation:[" + frame.getYRot() + "f," + pitch + "f]";
	}

	static Identifier parseId(String raw) {
		String id = raw.trim().toLowerCase(Locale.ROOT);
		int bracket = id.indexOf('[');
		if (bracket >= 0) {
			id = id.substring(0, bracket);
		}
		return Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
	}

	private static void create(ServerPlayer player, CreateDisplayPayload p) {
		if (!DisplayEntitiesEditor.checkPermission(player) || !DisplayEntitiesEditor.allow(player, "action", 20)) {
			return;
		}
		Entity found = player.level().getEntity(p.frameId());
		if (!(found instanceof ItemFrame frame) || !frame.getItem().isEmpty() || frame.distanceToSqr(player) > MAX_DISTANCE_SQR) {
			tell(player, "That item frame is gone, occupied or too far away.");
			return;
		}
		String content = p.content().strip();
		if (content.isEmpty()) {
			tell(player, "Nothing to display.");
			return;
		}

		Vec3 pos = frame.position();
		EntityType<?> type;
		String snbt;
		switch (p.kind()) {
			case TEXT -> {
				type = EntityTypes.TEXT_DISPLAY;
				snbt = uprightHead(frame, pos, 0.25) + ",text:" + StringTag.quoteAndEscape(content) + "}";
			}
			case BLOCK -> {
				Identifier id = parseId(content);
				if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
					tell(player, "Unknown block: " + content);
					return;
				}
				String state = content.toLowerCase(Locale.ROOT).contains(":") ? content.trim() : "minecraft:" + content.trim();
				type = EntityTypes.BLOCK_DISPLAY;
				// full-size block centred on the frame
				snbt = "{Pos:[" + pos.x + "d," + pos.y + "d," + pos.z + "d],block_state:" + StringTag.quoteAndEscape(state)
						+ ",transformation:{translation:[-0.5f,-0.5f,-0.5f],scale:[1f,1f,1f],"
						+ "left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}";
			}
			case ITEM -> {
				Identifier id = parseId(content);
				if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
					tell(player, "Unknown item: " + content);
					return;
				}
				type = EntityTypes.ITEM_DISPLAY;
				snbt = uprightHead(frame, pos, 0.5) + ",item:{id:" + StringTag.quoteAndEscape(id.toString()) + ",count:1}}";
			}
			default -> {
				return;
			}
		}

		ServerLevel level = player.level();
		CompoundTag tag;
		try {
			tag = TagParser.parseCompoundFully(snbt);
		} catch (CommandSyntaxException e) {
			tell(player, "Invalid value: " + content);
			return;
		}
		Entity display = EntityType.create(type, TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
				level, EntitySpawnReason.COMMAND).orElse(null);
		if (display == null) {
			tell(player, "Could not create the display.");
			return;
		}
		display.addTag(DisplayEntitiesEditor.FROM_FRAME_TAG);
		frame.discard();
		if (level.addFreshEntity(display)) {
			ServerPlayNetworking.send(player, new SelectPayload(display.getId()));
		}
	}
}
