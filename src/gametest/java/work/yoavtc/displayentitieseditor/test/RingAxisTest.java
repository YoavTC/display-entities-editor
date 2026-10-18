package work.yoavtc.displayentitieseditor.test;

import work.yoavtc.displayentitieseditor.client.GizmoController;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** For each ring colour (red/green/blue), drag it and report which world axis the entity actually rotated about. */
public class RingAxisTest implements FabricClientGameTest {
	private static final String[] NAMES = {"red", "green", "blue"};
	private static final Vec3[] AXES = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};

	private static void rmb(ClientGameTestContext context, boolean down) {
		context.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), down));
	}

	private static void aimAt(ClientGameTestContext context, Vec3 target) {
		float[] angles = context.computeOnClient(mc -> {
			Vec3 eye = mc.player.getEyePosition();
			double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
			double hd = Math.sqrt(dx * dx + dz * dz);
			return new float[] {(float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, hd))};
		});
		context.runOnClient(mc -> {
			mc.player.setYRot(angles[0]);
			mc.player.setXRot(angles[1]);
		});
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runOnServer(server -> ((net.minecraft.client.server.IntegratedServer) server).setWorldAllowCommands(true));
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			for (int axis = 0; axis < 3; axis++) {
				Vec3 pivot = new Vec3(20 * axis + 0.5, axis == 1 ? -58.38 : -60.5, 30.5);
				sp.getServer().runCommand("summon block_display " + (pivot.x - 0.5) + " " + (pivot.y - 0.5) + " " + (pivot.z - 0.5)
						+ " {block_state:\"minecraft:tnt\"}");
				// camera 4 blocks away along the ring's axis, looking back at the pivot
				Vec3[] offsets = {new Vec3(3, 0, 0), new Vec3(0, 0, 3), new Vec3(0, 0, 3)};
				Vec3 cam = pivot.add(offsets[axis]);
				sp.getServer().runCommand("tp Player0 " + cam.x + " " + -60 + " " + cam.z);
				context.waitTicks(30);
				int id = sp.getServer().computeOnServer(server -> {
					int best = -1;
					double bestD = Double.MAX_VALUE;
					for (var level : server.getAllLevels()) {
						for (Display d : level.getEntitiesOfClass(Display.class, new AABB(pivot, pivot).inflate(2))) {
							double dd = d.position().distanceTo(pivot);
							if (dd < bestD) {
								bestD = dd;
								best = d.getId();
							}
						}
					}
					return best;
				});
				aimAt(context, pivot);
				context.waitTicks(5);
				context.runOnClient(mc -> {
					GizmoController.setMode(GizmoController.Mode.ROTATE);
					GizmoController.selectById(id);
				});
				context.waitTicks(8);

				// pick a point on the ring whose normal is AXES[axis]
				Vec3 u = AXES[(axis + 1) % 3];
				Vec3 v = AXES[(axis + 2) % 3];
				double dist = context.computeOnClient(mc -> mc.gameRenderer.mainCamera().position().distanceTo(pivot));
				double r = Math.max(0.3, dist * 0.22) * 0.9;
				aimAt(context, pivot.add(u.add(v).scale(r * Math.sqrt(0.5))));
				context.waitTicks(4);
				System.out.println("[ring-test] " + NAMES[axis] + ": hover=" + context.computeOnClient(mc -> GizmoController.hoverHandle() + " cam=" + mc.gameRenderer.mainCamera().position() + " pivot=" + GizmoController.pivotWorld() + " sel=" + GizmoController.selected));
				context.takeScreenshot("ring-" + NAMES[axis]);
				rmb(context, true);
				context.waitTicks(3);
				for (int deg = 60; deg <= 90; deg += 15) {
					double a = Math.toRadians(deg);
					aimAt(context, pivot.add(u.scale(r * Math.cos(a))).add(v.scale(r * Math.sin(a))));
					context.waitTicks(2);
				}
				rmb(context, false);
				context.waitTicks(15);
				Quaternionf q = sp.getServer().computeOnServer(server -> {
					var d = (Display) server.getAllLevels().iterator().next().getEntity(id);
					return new Quaternionf(Display.createTransformation(d.getEntityData()).leftRotation());
				});
				System.out.println("[ring-test] " + NAMES[axis] + " ring -> left_rotation x=" + q.x + " y=" + q.y + " z=" + q.z + " w=" + q.w);
			}
		}
	}
}
