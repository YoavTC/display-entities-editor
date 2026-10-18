package work.yoavtc.displayentitieseditor.net;

import work.yoavtc.displayentitieseditor.DisplayEntitiesEditor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: replace the item frame with a display of the given kind showing {@code content}. */
public record CreateDisplayPayload(int frameId, Kind kind, String content) implements CustomPacketPayload {
	public enum Kind {
		TEXT, BLOCK, ITEM
	}

	public static final int MAX_CONTENT = 512;
	public static final Type<CreateDisplayPayload> TYPE = new Type<>(DisplayEntitiesEditor.id("create_display"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CreateDisplayPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.frameId);
				buf.writeVarInt(p.kind.ordinal());
				buf.writeUtf(p.content, MAX_CONTENT);
			},
			buf -> {
				int id = buf.readVarInt();
				int kind = buf.readVarInt();
				String content = buf.readUtf(MAX_CONTENT);
				Kind[] kinds = Kind.values();
				return new CreateDisplayPayload(id, kinds[Math.floorMod(kind, kinds.length)], content);
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
