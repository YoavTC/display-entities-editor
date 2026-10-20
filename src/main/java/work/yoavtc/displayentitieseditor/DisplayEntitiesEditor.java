package work.yoavtc.displayentitieseditor;

import com.mojang.math.Transformation;
import work.yoavtc.displayentitieseditor.net.CreateDisplayPayload;
import work.yoavtc.displayentitieseditor.net.OpenMenuPayload;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload;
import work.yoavtc.displayentitieseditor.net.SelectPayload;
import work.yoavtc.displayentitieseditor.net.TransformPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Entry point plus the server-side rules every client request has to pass. Nothing a client sends is trusted:
 * the player must hold the wand (and be an operator unless the config allows everyone), near the display, and within rate limits.
 */
public class DisplayEntitiesEditor implements ModInitializer {
	public static final String MOD_ID = "display_entities_editor";
	public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);

	/** Farthest a player may be from a display they edit. */
	static final double MAX_PLAYER_DISTANCE = 64.0;
	private static final float MAX_SCALE = 100f;
	private static final double MAX_HORIZONTAL = 3.0E7;
	private static final int MAX_TRANSFORMS_PER_SECOND = 80;
	private static final int MAX_ACTIONS_PER_SECOND = 20;

	private static final Map<UUID, Long> LAST_DENIED = new HashMap<>();
	private static final Map<String, long[]> RATE = new HashMap<>();

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(TransformPayload.TYPE, TransformPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(QuickActionPayload.TYPE, QuickActionPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SelectPayload.TYPE, SelectPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenMenuPayload.TYPE, OpenMenuPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CreateDisplayPayload.TYPE, CreateDisplayPayload.CODEC);
		DisplayEntitiesEditorConfig.load();
		FrameConversion.register();
		QuickActions.register();

		ServerPlayNetworking.registerGlobalReceiver(TransformPayload.TYPE, (payload, context) ->
				context.server().execute(() -> applyTransform(context.player(), payload)));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.getPlayer().getUUID();
			LAST_DENIED.remove(id);
			RATE.keySet().removeIf(k -> k.startsWith(id + "/"));
			EditSmoothing.playerLeft(id);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> EditSmoothing.tick());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> EditSmoothing.restoreAll());
	}

	private static void deny(ServerPlayer player, String message) {
		long now = System.currentTimeMillis();
		if (now - LAST_DENIED.getOrDefault(player.getUUID(), 0L) > 5000) {
			LAST_DENIED.put(player.getUUID(), now);
			player.sendSystemMessage(Component.literal("[Display Entities Editor] " + message));
		}
	}

	/** Every edit requires operator permissions and the wand in hand. */
	public static boolean checkPermission(ServerPlayer player) {
		if (!DisplayEntitiesEditorConfig.allowNonOperators() && !player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			deny(player, "Editing display entities requires operator permissions (cheats).");
			return false;
		}
		if (!DisplayEntitiesEditorConfig.isWand(player.getMainHandItem()) && !DisplayEntitiesEditorConfig.isWand(player.getOffhandItem())) {
			deny(player, "Hold the wand (" + DisplayEntitiesEditorConfig.wandItem() + ") to edit display entities.");
			return false;
		}
		return true;
	}

	/** Simple fixed-window rate limit per player and bucket. */
	static boolean allow(ServerPlayer player, String bucket, int perSecond) {
		long now = System.currentTimeMillis();
		long[] w = RATE.computeIfAbsent(player.getUUID() + "/" + bucket, k -> new long[] {now, 0});
		if (now - w[0] >= 1000) {
			w[0] = now;
			w[1] = 0;
		}
		return ++w[1] <= perSecond;
	}

	/** Permission, rate limit and distance checks shared by every action on an existing display. */
	static Display findEditable(ServerPlayer player, int entityId) {
		if (!checkPermission(player) || !allow(player, "action", MAX_ACTIONS_PER_SECOND)) {
			return null;
		}
		return lookup(player, entityId);
	}

	private static Display lookup(ServerPlayer player, int entityId) {
		Entity entity = player.level().getEntity(entityId);
		if (!(entity instanceof Display display) || display.distanceToSqr(player) > MAX_PLAYER_DISTANCE * MAX_PLAYER_DISTANCE) {
			return null;
		}
		return display;
	}

	private static boolean finite(float... values) {
		for (float v : values) {
			if (!Float.isFinite(v)) {
				return false;
			}
		}
		return true;
	}

	/** Visual centre of a display for a given position and transformation (block displays draw from a corner). */
	static Vec3 centerOf(Vec3 pos, Transformation t, boolean block) {
		float c = block ? 0.5f : 0f;
		Vector3f v = new Vector3f(c, c, c);
		t.getMatrixCopy().transformPosition(v);
		return pos.add(v.x, v.y, v.z);
	}

	private static void applyTransform(ServerPlayer player, TransformPayload p) {
		if (!checkPermission(player) || !allow(player, "transform", MAX_TRANSFORMS_PER_SECOND)) {
			return;
		}
		Display display = lookup(player, p.entityId());
		if (display == null || !finite(p.qx(), p.qy(), p.qz(), p.qw(), p.sx(), p.sy(), p.sz())
				|| !Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())) {
			return;
		}
		if (Math.abs(p.sx()) > MAX_SCALE || Math.abs(p.sy()) > MAX_SCALE || Math.abs(p.sz()) > MAX_SCALE
				|| Math.abs(p.x()) > MAX_HORIZONTAL || Math.abs(p.z()) > MAX_HORIZONTAL
				|| p.y() < display.level().getMinY() - 64 || p.y() > display.level().getMaxY() + 64) {
			return;
		}
		Transformation old = Display.createTransformation(display.getEntityData());
		Quaternionf left = new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw());
		if (left.lengthSquared() < 1.0e-6f) {
			return;
		}
		left.normalize();
		Transformation next = new Transformation(
				new Vector3f(old.translation()), left, new Vector3f(p.sx(), p.sy(), p.sz()), new Quaternionf(old.rightRotation()));

		EditSmoothing.touch(player, display);
		display.setTransformation(next);
		display.setPos(p.x(), p.y(), p.z());
	}
}
