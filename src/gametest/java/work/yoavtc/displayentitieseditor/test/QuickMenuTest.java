package work.yoavtc.displayentitieseditor.test;

import work.yoavtc.displayentitieseditor.client.BrowserScreen;
import work.yoavtc.displayentitieseditor.client.GizmoController;
import work.yoavtc.displayentitieseditor.client.QuickMenuScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** LMB editing, the sneak+click quick menu and its actions, and delete not dropping a frame for command-summoned displays. */
public class QuickMenuTest implements FabricClientGameTest {
	private static final AABB ALL = new AABB(-200, -64, -200, 200, 320, 200);

	private static void key(ClientGameTestContext c, boolean attack, boolean down) {
		c.runOnClient(mc -> KeyMapping.set((attack ? mc.options.keyAttack : mc.options.keyUse).getDefaultKey(), down));
	}

	private static void sneak(ClientGameTestContext c, boolean down) {
		c.runOnClient(mc -> KeyMapping.set(mc.options.keyShift.getDefaultKey(), down));
	}

	private static void aimAt(ClientGameTestContext context, Vec3 target, float yawOffset) {
		float[] a = context.computeOnClient(mc -> {
			Vec3 eye = mc.player.getEyePosition();
			double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
			return new float[] {(float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))};
		});
		context.runOnClient(mc -> {
			mc.player.setYRot(a[0] + yawOffset);
			mc.player.setXRot(a[1]);
		});
	}

	private static String state(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			for (var level : server.getAllLevels()) {
				for (Display d : level.getEntitiesOfClass(Display.class, ALL)) {
					var t = Display.createTransformation(d.getEntityData());
					return "pos=" + d.position() + " left=" + t.leftRotation() + " scale=" + t.scale();
				}
			}
			return "none";
		});
	}

	private static void openMenuBySneakClick(ClientGameTestContext context) {
		Vec3 c = context.computeOnClient(mc -> GizmoController.pivotWorld());
		sneak(context, true);
		context.waitTicks(4);
		aimAt(context, c, 0);
		context.waitTicks(3);
		key(context, false, true);
		context.waitTicks(4);
		key(context, false, false);
		sneak(context, false);
		context.waitForScreen(QuickMenuScreen.class);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runOnServer(server -> ((net.minecraft.client.server.IntegratedServer) server).setWorldAllowCommands(true));
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			sp.getServer().runCommand("tp Player0 0.5 -60 0.5 0 0");
			sp.getServer().runCommand("summon block_display 0 -59 3 {block_state:\"minecraft:tnt\",transformation:{translation:[0f,0f,0f],scale:[2f,2f,2f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f]}}");
			context.waitTicks(40);
			System.out.println("[quick-test] start " + state(sp));

			// ---- LMB selects and drags
			aimAt(context, new Vec3(1.0, -58.0, 4.0), 0);
			context.waitTicks(5);
			key(context, true, true);
			context.waitTicks(4);
			key(context, true, false);
			context.waitTicks(4);
			System.out.println("[quick-test] selected via LMB: " + context.computeOnClient(mc -> GizmoController.selected != null));
			key(context, true, true);
			context.waitTicks(3);
			for (int i = 1; i <= 4; i++) {
				aimAt(context, new Vec3(1.0, -58.0, 4.0), -3f * i);
				context.waitTicks(2);
			}
			key(context, true, false);
			context.waitTicks(15);
			System.out.println("[quick-test] after LMB drag " + state(sp));
			context.takeScreenshot("q1-help");

			// ---- reset scale
			openMenuBySneakClick(context);
			context.takeScreenshot("q2-menu");
			context.clickScreenButton("Reset Scale");
			context.waitTicks(15);
			System.out.println("[quick-test] after reset scale " + state(sp));

			// ---- reset rotation
			sp.getServer().runCommand("data modify entity @e[type=minecraft:block_display,limit=1] transformation.left_rotation set value [0f,0f,0.38f,0.92f]");
			context.waitTicks(10);
			System.out.println("[quick-test] rotated " + state(sp));
			openMenuBySneakClick(context);
			context.clickScreenButton("Reset Rotation");
			context.waitTicks(15);
			System.out.println("[quick-test] after reset rotation " + state(sp));

			// ---- align to block
			openMenuBySneakClick(context);
			context.clickScreenButton("Align Position to Block");
			context.waitTicks(15);
			System.out.println("[quick-test] after align " + state(sp));

			// ---- change block
			openMenuBySneakClick(context);
			context.clickScreenButton("Change Block");
			context.waitForScreen(BrowserScreen.class);
			context.getInput().typeChars("gold_block");
			context.waitTicks(3);
			double[] px = context.computeOnClient(mc -> {
				var sc = mc.gui.screen();
				double scale = mc.getWindow().getGuiScale();
				return new double[] {((sc.width - 240) / 2 + 10) * scale, 60 * scale};
			});
			context.getInput().setCursorPos(px[0], px[1]);
			context.waitTicks(3);
			context.getInput().pressMouse(1);
			context.waitTicks(20);
			context.takeScreenshot("q3-gold");

			// ---- delete of a command-summoned display drops nothing (only frame-converted displays drop a frame)
			openMenuBySneakClick(context);
			context.clickScreenButton("Delete");
			context.waitTicks(20);
			int[] counts = sp.getServer().computeOnServer(server -> {
				int displays = 0, frames = 0;
				for (var level : server.getAllLevels()) {
					displays += level.getEntitiesOfClass(Display.class, ALL).size();
					for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, ALL)) {
						if (e.getItem().is(net.minecraft.world.item.Items.ITEM_FRAME)) {
							frames++;
						}
					}
				}
				return new int[] {displays, frames};
			});
			System.out.println("[quick-test] after delete displays=" + counts[0] + " droppedFrames=" + counts[1] + (counts[0] == 0 && counts[1] == 0 ? " PASS" : " FAIL expected 0 displays and 0 frames"));
		}
	}
}
