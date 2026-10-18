package work.yoavtc.displayentitieseditor.test;

import work.yoavtc.displayentitieseditor.client.GizmoController;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The editing ray only travels 5 blocks: farther displays can't be picked, nearer ones can. */
public class RayRangeTest implements FabricClientGameTest {
	private static void click(ClientGameTestContext c) {
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
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runOnServer(server -> ((net.minecraft.client.server.IntegratedServer) server).setWorldAllowCommands(true));
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			sp.getServer().runCommand("tp Player0 0.5 -60 0.5 0 0");
			sp.getServer().runCommand("summon block_display 0 -59 8 {block_state:\"minecraft:tnt\"}");
			context.waitTicks(40);
			Vec3 center = new Vec3(0.5, -58.5, 8.5);

			aimAt(context, center);
			context.waitTicks(5);
			click(context);
			System.out.println("[range-test] selected at ~8 blocks: " + context.computeOnClient(mc -> GizmoController.selected != null) + " (expect false)");

			sp.getServer().runCommand("tp Player0 0.5 -60 4.5");
			context.waitTicks(10);
			aimAt(context, center);
			context.waitTicks(5);
			click(context);
			System.out.println("[range-test] selected at ~4 blocks: " + context.computeOnClient(mc -> GizmoController.selected != null) + " (expect true)");

			// back away: the selected display's handles can no longer be hovered or dragged
			sp.getServer().runCommand("tp Player0 0.5 -60 0.5");
			context.waitTicks(10);
			aimAt(context, center);
			context.waitTicks(5);
			System.out.println("[range-test] hover at ~8 blocks: " + context.computeOnClient(mc -> GizmoController.hoverHandle()) + " (expect -1)");
			Vec3 before = sp.getServer().computeOnServer(server -> {
				for (var level : server.getAllLevels()) {
					for (Display d : level.getEntitiesOfClass(Display.class, new AABB(-50, -64, -50, 50, 320, 50))) {
						return d.position();
					}
				}
				return null;
			});
			context.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), true));
			context.waitTicks(10);
			context.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), false));
			context.waitTicks(10);
			Vec3 after = sp.getServer().computeOnServer(server -> {
				for (var level : server.getAllLevels()) {
					for (Display d : level.getEntitiesOfClass(Display.class, new AABB(-50, -64, -50, 50, 320, 50))) {
						return d.position();
					}
				}
				return null;
			});
			System.out.println("[range-test] display moved while out of range: " + !before.equals(after) + " (expect false)");

			// ---- hold the centre cube and walk away: the display keeps following the crosshair at <= 5 blocks
			sp.getServer().runCommand("kill @e[type=minecraft:block_display]");
			sp.getServer().runCommand("summon block_display 0 -59 3 {block_state:\"minecraft:tnt\"}");
			context.waitTicks(20);
			Vec3 c2 = new Vec3(1.0, -58.5, 4.0);
			context.runOnClient(mc -> GizmoController.selected = null);
			context.runOnClient(mc -> GizmoController.setMode(GizmoController.Mode.TRANSLATE));
			aimAt(context, c2);
			context.waitTicks(5);
			click(context);
			System.out.println("[range-test] selected near: " + context.computeOnClient(mc -> GizmoController.selected != null));
			context.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), true));
			context.waitTicks(4);
			StringBuilder sb = new StringBuilder();
			for (int back = 1; back <= 8; back++) {
				sp.getServer().runCommand("tp Player0 0.5 -60 " + (0.5 - back));
				context.waitTicks(5);
				double[] z = context.computeOnClient(mc -> new double[] {GizmoController.pivotWorld().z, mc.player.getZ()});
				sb.append(String.format(java.util.Locale.ROOT, "back%d: player z=%.1f display z=%.2f (dist %.2f)  ", back, z[1], z[0], z[0] - z[1]));
			}
			context.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), false));
			context.waitTicks(10);
			System.out.println("[range-test] walking away while holding: " + sb);
		}
	}
}
