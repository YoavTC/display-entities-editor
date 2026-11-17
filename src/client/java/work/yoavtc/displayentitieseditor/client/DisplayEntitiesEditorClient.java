package work.yoavtc.displayentitieseditor.client;

import com.mojang.blaze3d.platform.InputConstants;
import work.yoavtc.displayentitieseditor.DisplayEntitiesEditor;
import work.yoavtc.displayentitieseditor.DisplayEntitiesEditorConfig;
import work.yoavtc.displayentitieseditor.net.OpenMenuPayload;
import work.yoavtc.displayentitieseditor.net.SelectPayload;
import work.yoavtc.displayentitieseditor.net.TransformResultPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public class DisplayEntitiesEditorClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(DisplayEntitiesEditor.id("main"));
	static KeyMapping cycleModeKey;
	static KeyMapping cycleNudgeKey;
	static KeyMapping deleteKey;
	static KeyMapping undoKey;
	static KeyMapping copyKey;
	static KeyMapping pasteKey;

	@Override
	public void onInitializeClient() {
		cycleModeKey = register("cycle_mode", InputConstants.KEY_H);
		cycleNudgeKey = register("cycle_nudge", InputConstants.KEY_J);
		deleteKey = register("delete", InputConstants.KEY_DELETE);
		undoKey = register("undo", InputConstants.KEY_Z);
		copyKey = register("copy", InputConstants.KEY_C);
		pasteKey = register("paste", InputConstants.KEY_V);

		// before entities tick, so a server update that overwrote an unconfirmed local edit is undone before it is drawn
		ClientTickEvents.START_CLIENT_TICK.register(client -> GizmoController.updateLocalEdits());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Holding the wand is what turns edit mode on and off.
			boolean holding = client.player != null
					&& (DisplayEntitiesEditorConfig.isWand(client.player.getMainHandItem()) || DisplayEntitiesEditorConfig.isWand(client.player.getOffhandItem()));
			if (holding != GizmoController.active) {
				GizmoController.setActive(holding);
			}
			while (cycleModeKey.consumeClick()) {
				GizmoController.cycleMode();
			}
			while (cycleNudgeKey.consumeClick()) {
				GizmoController.cycleNudge();
			}
		});

		LevelRenderEvents.BEFORE_GIZMOS.register(ctx -> GizmoController.frame(Minecraft.getInstance().gameRenderer.mainCamera()));
		HudElementRegistry.addLast(DisplayEntitiesEditor.id("hud"), GizmoHud::render);
		ClientPlayNetworking.registerGlobalReceiver(SelectPayload.TYPE,
				(payload, context) -> GizmoController.selectById(payload.entityId()));
		ClientPlayNetworking.registerGlobalReceiver(TransformResultPayload.TYPE,
				(payload, context) -> GizmoController.onServerResult(payload));
		ClientPlayNetworking.registerGlobalReceiver(OpenMenuPayload.TYPE,
				(payload, context) -> context.client().setScreenAndShow(new ChooseTypeScreen(payload.frameId())));
	}

	private static KeyMapping register(String name, int key) {
		return KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key." + DisplayEntitiesEditor.MOD_ID + "." + name, key, CATEGORY));
	}
}
