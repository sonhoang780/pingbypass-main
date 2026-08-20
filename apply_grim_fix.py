import sys

content = open('src/main/java/eu/client/mixins/ClientPlayerEntityMixin.java', 'r', encoding='utf-8').read()

target_shadows = '''    @Shadow @Final public ClientPacketListener connection;

    @Shadow private float xRotLast;

    // Ported verbatim from NamiDevelopment/nami-public's MixinLocalPlayer
    // (sendMovementPackets1/2) -- see RotationManager.silentSyncRequired's own doc for why.
    private float originalSilentXRot;

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void silentSync(CallbackInfo ci) {
        if (!EUClient.ROTATION_MANAGER.isSilentSyncRequired()) return;

        this.originalSilentXRot = this.getXRot();
        this.xRotLast -= 4;
        float f = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
        float f2 = net.minecraft.util.Mth.clamp(this.originalSilentXRot + f, -90.0F, 90.0F);
        this.setXRot(f2);
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void silentSync(CallbackInfo ci) {
        if (!EUClient.ROTATION_MANAGER.isSilentSyncRequired()) return;

        this.setXRot(this.originalSilentXRot);
        EUClient.ROTATION_MANAGER.setSilentSyncRequired(false);
    }'''

replacement = '''    @Shadow @Final public ClientPacketListener connection;

    @Shadow private double xLast;
    @Shadow private double yLast1;
    @Shadow private double zLast;
    @Shadow private float yRotLast;
    @Shadow private float xRotLast;
    @Shadow private boolean lastOnGround;
    @Shadow private boolean wasHorizontalCollision;
    @Shadow private int positionReminder;

    @Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
    private void silentSync(CallbackInfo ci) {
        if (!EUClient.ROTATION_MANAGER.isSilentSyncRequired()) return;

        EUClient.ROTATION_MANAGER.setSilentSyncRequired(false);

        // GRIMAC & PAPER PERFECT SYNC:
        // Update Vanilla\\'s internal tracking variables so the NEXT tick calculates deltas seamlessly.
        // This prevents Delta Spikes/Invalid Movement checks on GrimAC while cancelling the redundant packet on Paper.
        this.xLast = this.getX();
        this.yLast1 = this.getY();
        this.zLast = this.getZ();
        this.yRotLast = this.getYRot();
        this.xRotLast = this.getXRot();
        this.lastOnGround = this.onGround();
        this.wasHorizontalCollision = this.horizontalCollision;
        this.positionReminder = 0;

        ci.cancel();
    }'''

content = content.replace(target_shadows, replacement)

with open('src/main/java/eu/client/mixins/ClientPlayerEntityMixin.java', 'w', encoding='utf-8') as f:
    f.write(content)
