package org.xcore.maprenderer;

import org.xcore.msav.MapSnapshot.Placed;

import java.util.List;

/**
 * How conveyors, ducts and conduits join their neighbours: a port of v160's {@code Autotiler}
 * ({@code buildBlending}, {@code transformCase}, {@code lookingAt}, {@code lookingAtEither},
 * {@code blendsArmored}) and of each block's {@code blends} and cap rules, over the decoded map
 * instead of live buildings.
 */
final class Tiling {
    private static final int[] DX = {1, 0, -1, 0}, DY = {0, 1, 0, -1};

    private final RenderContent content;
    private final List<Placed> placed;
    private final int[] owner;
    private final int width, height;
    /** StackConveyor state per placed index (MOVE for every other building). */
    private final byte[] stackState;
    static final byte MOVE = 0, LOAD = 1, UNLOAD = 2;

    /** The result for one building: sprite index, vertical flip and caps. */
    record Blend(int bits, boolean flip, boolean capped, boolean backCapped) {}

    /** A plastanium conveyor: its state (sprite index) and the sides that join (StackConveyorBuild.blendprox). */
    record Stack(int state, int joined) {}

    Tiling(RenderContent content, List<Placed> placed, int width, int height) {
        this.content = content;
        this.placed = placed;
        this.width = width;
        this.height = height;
        owner = new int[width * height];
        java.util.Arrays.fill(owner, -1);
        for (int i = 0; i < placed.size(); i++) {
            var building = placed.get(i);
            int size = building.block() >= 0 && building.block() < content.size.length ? content.size[building.block()] : 1;
            int offset = -(size - 1) / 2;
            for (int y = Math.max(0, building.y() + offset); y < Math.min(height, building.y() + offset + size); y++) {
                for (int x = Math.max(0, building.x() + offset); x < Math.min(width, building.x() + offset + size); x++) {
                    owner[x + y * width] = i;
                }
            }
        }
        // Each state depends on the neighbours' states, as StackConveyorBuild.onProximityUpdate sees
        // them; settle them in a few passes from "all moving".
        stackState = new byte[placed.size()];
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (int i = 0; i < placed.size(); i++) {
                var building = placed.get(i);
                if (!isStack(building)) continue;
                byte state = (byte) stack(building).state();
                changed |= state != stackState[i];
                stackState[i] = state;
            }
            if (!changed) break;
        }
    }

    private boolean isStack(Placed building) {
        int block = building.block();
        return block >= 0 && block < content.stack.length && content.stack[block];
    }

    private byte stateOf(Placed building) {
        int index = owner[building.x() + building.y() * width];
        return index < 0 ? MOVE : stackState[index];
    }

    /** Block.rotatedOutput, which a plastanium conveyor answers from its state. */
    private boolean rotatedOutput(Placed other) {
        return isStack(other) ? stateOf(other) != UNLOAD : content.rotatedOutput[other.block()];
    }

    /** StackConveyorBuild.onProximityUpdate: the state, then which sides join in that state. */
    Stack stack(Placed self) {
        int r = self.rotation() & 3;
        boolean router = content.outputRouter[self.block()];
        boolean front = stackBlends(self, r, 0, MOVE), b1 = stackBlends(self, r, 1, MOVE);
        boolean back = stackBlends(self, r, 2, MOVE), b3 = stackBlends(self, r, 3, MOVE);
        boolean straight = !(b1 || b3); // buildBlending case 0
        byte state = MOVE;
        Placed behind = nearby(self, r + 2), ahead = nearby(self, r);
        if (straight && front && (!back || (behind != null && isStack(behind) && stateOf(behind) == UNLOAD))) state = LOAD;
        if (router && straight && !front && back) state = UNLOAD;
        if (!router && !(ahead != null && isStack(ahead))) state = UNLOAD;
        int joined = 0;
        for (int i = 0; i < 4; i++) {
            Placed side = nearby(self, r - i);
            if (stackBlends(self, r, i, state)
                    && (state != UNLOAD || router || i == 0 || (side != null && isStack(side)))) joined |= 1 << i;
        }
        return new Stack(state, joined);
    }

    private boolean stackBlends(Placed self, int rotation, int direction, byte state) {
        Placed other = nearby(self, rotation - direction);
        if (other == null || other.team() != self.team()) return false;
        int block = other.block();
        if (state == LOAD) return content.outputsItems[block] && lookingAtEither(self, rotation, other);
        if (state == UNLOAD && !content.outputRouter[self.block()]) {
            boolean facing = facesSelf(self, other);
            return content.acceptsItems[block]
                    && (!content.noSideBlend[block] || lookingAtEither(self, rotation, other))
                    && (!(rotatedOutput(other) && facing) || (isStack(other) && facing))
                    && !(isStack(other) && stateOf(other) == UNLOAD)
                    && !(isStack(other) && stateOf(other) == MOVE && !facing);
        }
        return content.outputsItems[block] && blendsArmored(self, rotation, other, true) && isStack(other);
    }

    private Placed at(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return null;
        int index = owner[x + y * width];
        return index < 0 ? null : placed.get(index);
    }

    private Placed nearby(Placed self, int direction) {
        int d = Math.floorMod(direction, 4);
        return at(self.x() + DX[d], self.y() + DY[d]);
    }

    Blend blend(Placed self) {
        int r = self.rotation() & 3;
        boolean b1 = blends(self, r, 1), b2 = blends(self, r, 2), b3 = blends(self, r, 3);
        int bits = 0;
        boolean flip = false;
        // Autotiler.buildBlending, then transformCase.
        if (b2 && b1 && b3) bits = 3;
        else if (b1 && b3) bits = 4;
        else if (b1 && b2) bits = 2;
        else if (b3 && b2) { bits = 2; flip = true; }
        else if (b1) { bits = 1; flip = true; }
        else if (b3) bits = 1;

        byte kind = content.tiling[self.block()];
        boolean liquid = kind == RenderContent.CONDUIT || kind == RenderContent.ARMORED_CONDUIT;
        Placed next = nearby(self, r), previous = nearby(self, r + 2);
        boolean capped = !carries(self, next, liquid);
        boolean backCapped = bits == 0 && !carries(self, previous, liquid);
        return new Blend(bits, flip, capped, backCapped);
    }

    private boolean carries(Placed self, Placed other, boolean liquid) {
        return other != null && other.team() == self.team()
                && (liquid ? content.hasLiquids[other.block()] : content.hasItems[other.block()]);
    }

    /** Autotiler.blends(tile, rotation, direction): the building on that side, if it joins this one. */
    private boolean blends(Placed self, int rotation, int direction) {
        Placed other = nearby(self, rotation - direction);
        return other != null && other.team() == self.team() && blends(self, rotation, other);
    }

    private boolean blends(Placed self, int rotation, Placed other) {
        int block = other.block();
        boolean lookingAt = lookingAt(self, rotation, other);
        return switch (content.tiling[self.block()]) {
            case RenderContent.CONVEYOR, RenderContent.DUCT ->
                    (content.outputsItems[block] || (lookingAt && content.hasItems[block])) && lookingAtEither(self, rotation, other);
            case RenderContent.ARMORED_CONVEYOR ->
                    (content.outputsItems[block] && blendsArmored(self, rotation, other, content.conveyor[block]))
                            || (lookingAt && content.hasItems[block]);
            case RenderContent.ARMORED_DUCT ->
                    (content.outputsItems[block] && blendsArmored(self, rotation, other, content.isDuct[block]))
                            || (lookingAt && content.hasItems[block]);
            case RenderContent.CONDUIT ->
                    content.hasLiquids[block] && (content.outputsLiquid[block] || lookingAt) && lookingAtEither(self, rotation, other);
            case RenderContent.ARMORED_CONDUIT ->
                    (content.outputsLiquid[block] && blendsArmored(self, rotation, other, true))
                            || (lookingAt && content.hasLiquids[block]) || content.liquidJunction[block];
            default -> false;
        };
    }

    /** Edges.getFacingEdge: the tile of the other building nearest to this one. */
    private int[] facing(Placed self, Placed other) {
        int size = content.size[other.block()];
        if (size <= 1) return new int[]{other.x(), other.y()};
        return new int[]{other.x() + Math.clamp(self.x() - other.x(), -(size - 1) / 2, size / 2),
                other.y() + Math.clamp(self.y() - other.y(), -(size - 1) / 2, size / 2)};
    }

    private boolean lookingAt(Placed self, int rotation, Placed other) {
        int[] facing = facing(self, other);
        return facing[0] == self.x() + DX[rotation] && facing[1] == self.y() + DY[rotation];
    }

    private boolean lookingAtEither(Placed self, int rotation, Placed other) {
        return (self.x() + DX[rotation] == other.x() && self.y() + DY[rotation] == other.y())
                || !rotatedOutput(other)
                || facesSelf(self, other);
    }

    private boolean blendsArmored(Placed self, int rotation, Placed other, boolean thirdCase) {
        if (self.x() + DX[rotation] == other.x() && self.y() + DY[rotation] == other.y()) return true;
        boolean rotated = rotatedOutput(other);
        if (!rotated) {
            int[] facing = facing(self, other);
            if (relativeTo(facing[0], facing[1], self.x(), self.y()) == rotation) return true;
        }
        return thirdCase && rotated && facesSelf(self, other);
    }

    private static boolean facesSelf(Placed self, Placed other) {
        int r = other.rotation() & 3;
        return other.x() + DX[r] == self.x() && other.y() + DY[r] == self.y();
    }

    /** Tile.relativeTo: the direction from (x, y) to the adjacent (cx, cy), or -1. */
    private static int relativeTo(int x, int y, int cx, int cy) {
        if (x == cx && y == cy - 1) return 1;
        if (x == cx && y == cy + 1) return 3;
        if (x == cx - 1 && y == cy) return 0;
        if (x == cx + 1 && y == cy) return 2;
        return -1;
    }
}
