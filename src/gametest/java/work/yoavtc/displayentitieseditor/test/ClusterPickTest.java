package work.yoavtc.displayentitieseditor.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.phys.Vec3;
import work.yoavtc.displayentitieseditor.client.GizmoController;

/** Displays packed closer together than their own size: looking at a centre marker selects exactly that display. */
public class ClusterPickTest implements FabricClientGameTest {
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
		context.waitTicks(4);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runOnServer(server -> ((net.minecraft.client.server.IntegratedServer) server).setWorldAllowCommands(true));
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			sp.getServer().runCommand("tp Player0 0.5 -60 0.5 0 0");
			// five unit-size item displays only 0.3 blocks apart in x, plus two stacked in depth: every box overlaps its neighbours
			double[][] spots = {{0.0, 3.0}, {0.3, 3.0}, {0.6, 3.0}, {0.9, 3.0}, {1.2, 3.0}, {0.6, 3.4}, {0.6, 3.8}};
			for (double[] s : spots) {
				sp.getServer().runCommand("summon item_display " + (0.5 + s[0]) + " -58.5 " + (0.5 + s[1]) + " {item:{id:\"minecraft:diamond\",count:1}}");
			}
			context.waitTicks(40);
			int ok = 0;
			for (double[] s : spots) {
				Vec3 centre = new Vec3(0.5 + s[0], -58.5, 0.5 + s[1]);
				context.runOnClient(mc -> GizmoController.selected = null);
				aimAt(context, centre);
				context.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), true));
				context.waitTicks(4);
				context.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), false));
				context.waitTicks(4);
				Vec3 got = context.computeOnClient(mc -> GizmoController.selected == null ? null : GizmoController.selected.position());
				boolean hit = got != null && got.distanceTo(centre) < 0.05;
				if (hit) {
					ok++;
				}
				System.out.println("[cluster-test] aimed at " + centre + " -> selected " + got + (hit ? " OK" : " WRONG"));
			}
			System.out.println("[cluster-test] correct picks: " + ok + "/" + spots.length);
		}
	}
}
