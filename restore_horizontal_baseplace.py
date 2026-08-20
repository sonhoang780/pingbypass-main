import sys

content = open('src/main/java/eu/client/modules/impl/combat/AutoCrystalModule.java', 'r', encoding='utf-8').read()

target_block = '''    private boolean executeBasePlace() {
        if (isDead()) return false;
        if (!basePlace.getValue()) return false;
        if (shouldPause("Place")) return false;

        Player targetPlayer = this.target;
        if (targetPlayer == null) {
            double bestDistSq = Double.MAX_VALUE;
            for (Player player : mc.level.players()) {
                eu.client.modules.impl.visuals.PopChamsModule popChams = eu.client.EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.PopChamsModule.class);
                if (popChams != null && popChams.isGhost(player)) continue;
                if (player == mc.player || !player.isAlive()) continue;
                if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) continue;
                double distSq = mc.player.distanceToSqr(player);
                if (distSq <= Mth.square(enemyRange.getValue().doubleValue()) && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    targetPlayer = player;
                }
            }
        }

        if (targetPlayer == null) return false;

        PlaceTarget existingPlacement = calculatePlacements(null);
        if (existingPlacement != null && existingPlacement.getPosition() != null) {
            BlockPos pos = existingPlacement.getPosition();
            BlockPos targetFeet = PositionUtils.getFlooredPosition(targetPlayer);
            if (pos.getY() >= targetFeet.below().getY() && existingPlacement.getDamage() >= getMinimumDamage(targetPlayer, minimumDamage.getValue().floatValue())) {
                return false;
            }
        }

        int obsidianSlot = basePlaceSwitch.getValue().equalsIgnoreCase("None") ? -1 :
                InventoryUtils.find(Items.OBSIDIAN, 0, basePlaceSwitch.getValue().equalsIgnoreCase("AltSwap") || basePlaceSwitch.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
        if (obsidianSlot == -1) return false;

        BlockPos targetFeet = PositionUtils.getFlooredPosition(targetPlayer);
        BlockPos bestBasePos = null;
        float bestDamage = 0.0f;
        double bestDistToPlayer = Double.MAX_VALUE;

        // Dynamic 3D search around target for highest-damage BasePlace position
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 3) continue;
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos candidate = targetFeet.offset(dx, dy, dz);
                    if (!isBaseCandidateValid(candidate)) continue;

                    Direction placeDir = WorldUtils.getDirection(candidate, false);
                    if (placeDir == null) placeDir = WorldUtils.getClosestDirection(candidate, true);
                    if (placeDir == null) continue;

                    float damage = DamageUtils.getCrystalDamage(targetPlayer, PositionUtils.extrapolate(targetPlayer, extrapolation.getValue().intValue()), candidate, null, ignoreTerrain.getValue());
                    if (damage < getMinimumDamage(targetPlayer, minimumDamage.getValue().floatValue()) && damage < targetPlayer.getHealth() + targetPlayer.getAbsorptionAmount()) {
                        continue;
                    }

                    if (!EUClient.MODULE_MANAGER.getModule(SuicideModule.class).isToggled()) {
                        float selfDamage = DamageUtils.getCrystalDamage(mc.player, null, candidate, null, ignoreTerrain.getValue());
                        if (selfDamage > maximumSelfDamage.getValue().floatValue()) continue;
                        if (antiSuicide.getValue() && selfDamage > mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;
                    }

                    if (damage > bestDamage || (damage == bestDamage && mc.player.distanceToSqr(Vec3.atCenterOf(candidate)) < bestDistToPlayer)) {
                        bestDamage = damage;
                        bestBasePos = candidate;
                        bestDistToPlayer = mc.player.distanceToSqr(Vec3.atCenterOf(candidate));
                    }
                }
            }
        }

        // Fallback: Place adjacent to target feet or under feet if no high-damage spot was filtered
        if (bestBasePos == null) {
            BlockPos block1 = targetFeet.below();
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos pos2 = block1.relative(dir);
                if (isBaseCandidateValid(pos2)) {
                    Direction placeDir = WorldUtils.getDirection(pos2, false);
                    if (placeDir != null || WorldUtils.getClosestDirection(pos2, true) != null) {
                        double dist = mc.player.distanceToSqr(Vec3.atCenterOf(pos2));
                        if (dist < bestDistToPlayer) {
                            bestDistToPlayer = dist;
                            bestBasePos = pos2;
                        }
                    }
                }
            }
        }

        if (bestBasePos == null) return false;

        Direction placeDir = WorldUtils.getDirection(bestBasePos, false);
        if (placeDir == null) placeDir = WorldUtils.getClosestDirection(bestBasePos, true);
        if (placeDir == null) placeDir = Direction.UP;

        int prevSlot = mc.player.getInventory().getSelectedSlot();
        InventoryUtils.switchSlot(basePlaceSwitch.getValue(), obsidianSlot, prevSlot);
        boolean placed = WorldUtils.placeBlock(bestBasePos, placeDir, InteractionHand.MAIN_HAND,
                basePlaceRotate.getValue(), true,
                renderMode.getValue().equalsIgnoreCase("Both") || renderMode.getValue().equalsIgnoreCase("Fill"));
        InventoryUtils.switchBack(basePlaceSwitch.getValue(), obsidianSlot, prevSlot);

        return placed;
    }'''

replacement_block = '''    private boolean executeBasePlace() {
        if (isDead()) return false;
        if (!basePlace.getValue()) return false;
        if (shouldPause("Place")) return false;

        Player targetPlayer = this.target;
        if (targetPlayer == null) {
            double bestDistSq = Double.MAX_VALUE;
            for (Player player : mc.level.players()) {
                eu.client.modules.impl.visuals.PopChamsModule popChams = eu.client.EUClient.MODULE_MANAGER.getModule(eu.client.modules.impl.visuals.PopChamsModule.class);
                if (popChams != null && popChams.isGhost(player)) continue;
                if (player == mc.player || !player.isAlive()) continue;
                if (EUClient.FRIEND_MANAGER.contains(player.getName().getString())) continue;
                double distSq = mc.player.distanceToSqr(player);
                if (distSq <= Mth.square(enemyRange.getValue().doubleValue()) && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    targetPlayer = player;
                }
            }
        }

        if (targetPlayer == null) return false;

        PlaceTarget existingPlacement = calculatePlacements(null);
        if (existingPlacement != null && existingPlacement.getPosition() != null) {
            BlockPos pos = existingPlacement.getPosition();
            BlockPos targetFeet = PositionUtils.getFlooredPosition(targetPlayer);
            if (pos.getY() >= targetFeet.below().getY() && existingPlacement.getDamage() >= getMinimumDamage(targetPlayer, minimumDamage.getValue().floatValue())) {
                return false;
            }
        }

        BlockPos targetFeet = PositionUtils.getFlooredPosition(targetPlayer);
        BlockPos block1 = targetFeet.below();
        BlockState state1 = mc.level.getBlockState(block1);
        if (state1.isAir() || state1.canBeReplaced()) return false;

        Direction bestDir = null;
        double bestDistToPlayer = Double.MAX_VALUE;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos pos2 = block1.relative(dir);
            BlockPos pos3 = block1.relative(dir, 2);

            boolean pos2Valid = isBaseCandidateValid(pos2);
            boolean pos3Valid = isBaseCandidateValid(pos3);

            if (pos2Valid && pos3Valid) {
                double dist = mc.player.distanceToSqr(Vec3.atCenterOf(pos2));
                if (dist < bestDistToPlayer) {
                    bestDistToPlayer = dist;
                    bestDir = dir;
                }
            }
        }

        if (bestDir == null) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos pos2 = block1.relative(dir);
                if (isBaseCandidateValid(pos2)) {
                    double dist = mc.player.distanceToSqr(Vec3.atCenterOf(pos2));
                    if (dist < bestDistToPlayer) {
                        bestDistToPlayer = dist;
                        bestDir = dir;
                    }
                }
            }
        }

        if (bestDir == null) return false;

        BlockPos pos2 = block1.relative(bestDir);
        BlockPos pos3 = block1.relative(bestDir, 2);

        BlockPos toPlace = null;
        BlockState state2 = mc.level.getBlockState(pos2);
        if (state2.getBlock() != Blocks.OBSIDIAN && state2.getBlock() != Blocks.BEDROCK) {
            if (WorldUtils.isPlaceable(pos2) && mc.player.distanceToSqr(Vec3.atCenterOf(pos2)) <= Mth.square(basePlaceRange.getValue().doubleValue())) {
                toPlace = pos2;
            }
        } else {
            BlockState state3 = mc.level.getBlockState(pos3);
            if (state3.getBlock() != Blocks.OBSIDIAN && state3.getBlock() != Blocks.BEDROCK) {
                if (WorldUtils.isPlaceable(pos3) && mc.player.distanceToSqr(Vec3.atCenterOf(pos3)) <= Mth.square(basePlaceRange.getValue().doubleValue())) {
                    toPlace = pos3;
                }
            }
        }

        if (toPlace == null) return false;

        int obsidianSlot = basePlaceSwitch.getValue().equalsIgnoreCase("None") ? -1 :
                InventoryUtils.find(Items.OBSIDIAN, 0, basePlaceSwitch.getValue().equalsIgnoreCase("AltSwap") || basePlaceSwitch.getValue().equalsIgnoreCase("AltPickup") ? InventoryUtils.INVENTORY_END : InventoryUtils.HOTBAR_END);
        if (obsidianSlot == -1) return false;

        Direction placeDir = WorldUtils.getDirection(toPlace, false);
        if (placeDir == null) placeDir = WorldUtils.getClosestDirection(toPlace, true);
        if (placeDir == null) placeDir = Direction.UP;

        int prevSlot = mc.player.getInventory().getSelectedSlot();
        InventoryUtils.switchSlot(basePlaceSwitch.getValue(), obsidianSlot, prevSlot);
        boolean placed = WorldUtils.placeBlock(toPlace, placeDir, InteractionHand.MAIN_HAND,
                basePlaceRotate.getValue(), true,
                renderMode.getValue().equalsIgnoreCase("Both") || renderMode.getValue().equalsIgnoreCase("Fill"));
        InventoryUtils.switchBack(basePlaceSwitch.getValue(), obsidianSlot, prevSlot);

        return placed;
    }'''

content = content.replace(target_block, replacement_block)

with open('src/main/java/eu/client/modules/impl/combat/AutoCrystalModule.java', 'w', encoding='utf-8') as f:
    f.write(content)
