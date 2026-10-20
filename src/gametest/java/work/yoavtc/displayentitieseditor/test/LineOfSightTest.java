package work.yoavtc.displayentitieseditor.test;

import work.yoavtc.displayentitieseditor.client.GizmoController;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.phys.Vec3;

/** A solid block between the camera and a display blocks selection; a display flush against a wall can still be picked. */
public class LineOfSightTest implements FabricClientGameTest {
	private static void click(ClientGameTestContext c) {
		c.runOnClient(mc -> GizmoController.selected = null);
		c.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), true));
		c.waitTicks(4);
		c.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), false));
		c.waitTicks(4);
	}

	private static void aimAt(ClientGameTestContext context, Vec3 target) {
		float[] a = context.computeOnClient(mc -> {
			Vec3 eye = mc.player.getEyePosition();
			double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
			return new float[] {(float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))};
		});
		context.runOnClient(mc -> {
			mc.player.setYRot(a[0]);
			mc.player.setXRot(a[1]);
		});
		context.waitTicks(5);
	}

	private static boolean trySelect(ClientGameTestContext context, Vec3 center) {
		aimAt(context, center);
		click(context);
		return context.computeOnClient(mc -> GizmoController.selected != null);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runOnServer(server -> ((net.minecraft.client.server.IntegratedServer) server).setWorldAllowCommands(true));
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			sp.getServer().runCommand("tp Player0 0.5 -60 0.5 0 0");
			sp.getServer().runCommand("summon block_display 0 -59 3 {block_state:\"minecraft:tnt\"}");
			sp.getServer().runCommand("setblock 0 -59 2 minecraft:stone");
			sp.getServer().runCommand("setblock 0 -58 2 minecraft:stone");
			context.waitTicks(40);
			Vec3 center = new Vec3(0.5, -58.5, 3.5);

			System.out.println("[los-test] selected behind stone: " + trySelect(context, center) + " (expect false)");

			sp.getServer().runCommand("setblock 0 -59 2 minecraft:air");
			sp.getServer().runCommand("setblock 0 -58 2 minecraft:air");
			context.waitTicks(10);
			System.out.println("[los-test] selected with block removed: " + trySelect(context, center) + " (expect true)");

			// display flush against a wall surface facing the player (wall at z=4, display occupies z 3..4)
			sp.getServer().runCommand("fill -1 -60 4 2 -56 4 minecraft:stone");
			context.waitTicks(10);
			System.out.println("[los-test] selected flush against wall: " + trySelect(context, center) + " (expect true)");
		}
	}
}
