package work.yoavtc.displayentitieseditor.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import work.yoavtc.displayentitieseditor.client.GizmoController;

/** Scrolling over a handle nudges by the selected amount; the nudge amount and the mode cycle. */
public class NudgeTest implements FabricClientGameTest {
	private static final AABB ALL = new AABB(-200, -64, -200, 200, 320, 200);

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

	private static double[] state(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			for (var level : server.getAllLevels()) {
				for (Display d : level.getEntitiesOfClass(Display.class, ALL)) {
					var t = Display.createTransformation(d.getEntityData());
					return new double[] {d.getY(), t.scale().x(), t.leftRotation().z(), t.leftRotation().w()};
				}
			}
			return new double[4];
		});
	}

	private static void scroll(ClientGameTestContext c, double dy) {
		c.runOnClient(mc -> GizmoController.onScroll(dy));
		c.waitTicks(6);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runOnServer(server -> ((net.minecraft.client.server.IntegratedServer) server).setWorldAllowCommands(true));
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			sp.getServer().runCommand("tp Player0 0.5 -60 0.5 0 0");
			sp.getServer().runCommand("summon block_display 0 -59 3 {block_state:\"minecraft:tnt\"}");
			context.waitTicks(40);
			Vec3 center = new Vec3(1.0, -58.5, 4.0);
			double size = Math.max(0.3, context.computeOnClient(mc -> mc.player.getEyePosition().distanceTo(center)) * 0.22);

			int id = sp.getServer().computeOnServer(server -> {
				for (var level : server.getAllLevels()) {
					for (Display d : level.getEntitiesOfClass(Display.class, ALL)) {
						return d.getId();
					}
				}
				return -1;
			});
			context.runOnClient(mc -> GizmoController.selectById(id));
			context.waitTicks(10);
			context.runOnClient(mc -> GizmoController.setMode(GizmoController.Mode.TRANSLATE));

			// ---- translate: scroll up over the green up arrow with each nudge amount
			aimAt(context, center.add(0, 0.5 * size, 0));
			System.out.println("[nudge-test] hover=" + context.computeOnClient(mc -> GizmoController.hoverHandle()) + " (expect 1 = up arrow)");
			double y0 = state(sp)[0];
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 4; i++) {
				String label = context.computeOnClient(mc -> GizmoController.nudgeLabel());
				Vec3 now = context.computeOnClient(mc -> GizmoController.pivotWorld());
				aimAt(context, now.add(0, 0.5 * size, 0)); // keep the crosshair on the arrow as the display moves
				double before = state(sp)[0];
				scroll(context, 1);
				sb.append(String.format(java.util.Locale.ROOT, "step %s -> dy=%.4f; ", label, state(sp)[0] - before));
				context.runOnClient(mc -> GizmoController.cycleNudge());
			}
			Vec3 nowDown = context.computeOnClient(mc -> GizmoController.pivotWorld());
			aimAt(context, nowDown.add(0, 0.5 * size, 0));
			scroll(context, -1);
			sb.append(String.format(java.util.Locale.ROOT, "scroll down at %s -> total dy=%.4f", context.computeOnClient(mc -> GizmoController.nudgeLabel()), state(sp)[0] - y0));
			System.out.println("[nudge-test] translate: " + sb);

			// ---- mode cycle
			StringBuilder modes = new StringBuilder();
			for (int i = 0; i < 4; i++) {
				String modeLabel = context.computeOnClient(mc -> GizmoController.mode.label);
				modes.append(modeLabel).append(" > ");
				context.runOnClient(mc -> GizmoController.cycleMode());
			}
			System.out.println("[nudge-test] mode cycle: " + modes);

			// ---- scale (local X handle) with 0.5
			context.runOnClient(mc -> GizmoController.setMode(GizmoController.Mode.SCALE));
			while (!context.computeOnClient(mc -> GizmoController.nudgeLabel()).equals("0.5")) {
				context.runOnClient(mc -> GizmoController.cycleNudge());
			}
			Vec3 c2 = context.computeOnClient(mc -> GizmoController.pivotWorld());
			aimAt(context, c2.add(0.6 * size, 0, 0));
			double s0 = state(sp)[1];
			scroll(context, 1);
			System.out.println("[nudge-test] scale x " + s0 + " -> " + state(sp)[1] + " (expect +0.5), hover=" + context.computeOnClient(mc -> GizmoController.hoverHandle()));

			// ---- rotate (Z ring) with 0.25 -> 22.5 degrees
			context.runOnClient(mc -> GizmoController.setMode(GizmoController.Mode.ROTATE));
			while (!context.computeOnClient(mc -> GizmoController.nudgeLabel()).equals("0.25")) {
				context.runOnClient(mc -> GizmoController.cycleNudge());
			}
			double dist = context.computeOnClient(mc -> mc.player.getEyePosition().distanceTo(c2));
			double r = Math.max(0.3, dist * 0.22) * 0.9;
			aimAt(context, c2.add(r * Math.cos(Math.toRadians(45)), r * Math.sin(Math.toRadians(45)), 0));
			scroll(context, 1);
			double[] st = state(sp);
			work.yoavtc.displayentitieseditor.DisplayEntitiesEditorConfig.setLineWidth(1.5f);
			context.takeScreenshot("n-width-1.5");
			work.yoavtc.displayentitieseditor.DisplayEntitiesEditorConfig.setLineWidth(6f);
			context.waitTicks(3);
			context.takeScreenshot("n-width-6");
			work.yoavtc.displayentitieseditor.DisplayEntitiesEditorConfig.setLineWidth(1.5f);
			System.out.println("[nudge-test] rotate about Z: " + Math.toDegrees(2 * Math.atan2(st[2], st[3])) + " degrees (expect 22.5), hover=" + context.computeOnClient(mc -> GizmoController.hoverHandle()));
		}
	}
}
