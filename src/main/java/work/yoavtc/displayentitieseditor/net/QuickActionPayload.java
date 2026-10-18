package work.yoavtc.displayentitieseditor.net;

import work.yoavtc.displayentitieseditor.DisplayEntitiesEditor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: a one-shot action on a display entity (delete, change content, reset, align). */
public record QuickActionPayload(int entityId, Action action, String arg) implements CustomPacketPayload {
	public enum Action {
		DELETE, SET_BLOCK, SET_ITEM, SET_TEXT, SET_TEXTURE, RESET_SCALE, RESET_ROTATION, ALIGN_TO_BLOCK
	}

	public static final int MAX_ARG = 2048;
	public static final Type<QuickActionPayload> TYPE = new Type<>(DisplayEntitiesEditor.id("quick_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, QuickActionPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.entityId);
				buf.writeVarInt(p.action.ordinal());
				buf.writeUtf(p.arg, MAX_ARG);
			},
			buf -> {
				int id = buf.readVarInt();
				int action = buf.readVarInt();
				String arg = buf.readUtf(MAX_ARG);
				Action[] actions = Action.values();
				return new QuickActionPayload(id, actions[Math.floorMod(action, actions.length)], arg);
			});

	public QuickActionPayload(int entityId, Action action) {
		this(entityId, action, "");
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
