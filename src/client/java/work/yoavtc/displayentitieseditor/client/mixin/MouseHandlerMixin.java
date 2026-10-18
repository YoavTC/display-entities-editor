package work.yoavtc.displayentitieseditor.client.mixin;

import work.yoavtc.displayentitieseditor.client.GizmoController;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	/** Scroll nudges the hovered gizmo handle instead of changing the hotbar slot. */
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void display_entities_editor$nudge(long window, double xOffset, double yOffset, CallbackInfo ci) {
		if (GizmoController.onScroll(yOffset)) {
			ci.cancel();
		}
	}
}
