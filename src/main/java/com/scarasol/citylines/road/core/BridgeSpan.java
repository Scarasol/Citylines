package com.scarasol.citylines.road.core;

/**
 * A city bridge over a short water gap, along one axis.
 *
 * <p>Spans are <b>independent of the planar edge signature</b>: they are decided by
 * the sparse corridor scan before any routing happens, and both shores become hard
 * terminals of their own components. A span only exists when the pure predicate
 * {@code P(span)} holds, which either side can recompute from the same frozen
 * facts, so a half bridge is impossible by construction.
 *
 * @param axis   axis the deck runs along
 * @param fixed  the constant coordinate (z for {@link Axis#X}, x for {@link Axis#Z})
 * @param from   lower coordinate along the axis (inclusive)
 * @param to     higher coordinate along the axis (inclusive)
 */
public record BridgeSpan(Axis axis, int fixed, int from, int to) {

    public BridgeSpan {
        if (to <= from) {
            throw new IllegalArgumentException("empty span: " + from + ".." + to);
        }
    }

    public int length() {
        return to - from;
    }

    /** Cells of the span, from the low shore to the high shore (both inclusive). */
    public Cell lowShore() {
        return axis == Axis.X ? new Cell(from, fixed) : new Cell(fixed, from);
    }

    public Cell highShore() {
        return axis == Axis.X ? new Cell(to, fixed) : new Cell(fixed, to);
    }

    /** Water cells strictly between the two shores. */
    public int waterCells() {
        return to - from - 1;
    }

    /** Canonical identity; identical from either shore. */
    public long identity() {
        return Hash.combine(axis.ordinal(), Hash.combine(fixed, Hash.combine(from, to)));
    }

    public boolean containsWaterCell(Cell cell) {
        if (axis == Axis.X) {
            return cell.z() == fixed && cell.x() > from && cell.x() < to;
        }
        return cell.x() == fixed && cell.z() > from && cell.z() < to;
    }

    public boolean touches(Cell cell) {
        if (axis == Axis.X) {
            return cell.z() == fixed && cell.x() >= from && cell.x() <= to;
        }
        return cell.x() == fixed && cell.z() >= from && cell.z() <= to;
    }

    @Override
    public String toString() {
        return "Bridge[" + axis + " " + fixed + " " + from + ".." + to + "]";
    }
}
