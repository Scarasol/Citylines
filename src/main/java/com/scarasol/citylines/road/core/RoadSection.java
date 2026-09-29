package com.scarasol.citylines.road.core;

/** Current three-tier cross-sections; each fills one 16-block chunk. */
public record RoadSection(int walk, int kerb, int carriageway) {

    public RoadSection {
        if (walk < 0 || kerb < 0 || carriageway < 0) {
            throw new IllegalArgumentException("negative section width");
        }
    }

    /**
     * Arterial section: walk 1 + kerb 1 + carriageway 12 + kerb 1 + walk 1.
     *
     * <p>The widest class and the only one that carries the centre line (decision D4): the
     * carriageway is 12 blocks wide, so its middle is the <b>two</b> blocks at offset 7 and 8 -
     * an even width has no single centre block - and those two cells are painted as the centre
     * line. The section keeps a one-block verge instead of a shoulder, which is what makes it read
     * as a highway rather than as a wider version of the collector.
     */
    public static final RoadSection PRIMARY = new RoadSection(1, 1, 12);
    /**
     * Collector section: walk 2 + kerb 1 + carriageway 10 + kerb 1 + walk 2.
     *
     * <p>This is the form the arterial used to have, kept for the middle tier (decision D4) and
     * without a centre line, so the two upper tiers differ in both width and markings.
     */
    public static final RoadSection SECONDARY = new RoadSection(2, 1, 10);
    /**
     * Local access section: walk 4 + kerb 1 + carriageway 6 + kerb 1 + walk 4.
     *
     * <p>The narrowest class, one step below the collector on every band: a 6-block
     * carriageway still fits two-way traffic and the 4-block walk keeps the pavement
     * usable. It is also the one tier whose carriageway uses a different material
     * (light stone instead of concrete), so the three levels differ in width, in
     * markings and in surface - the knowledge-base requirement for V3.
     */
    public static final RoadSection TERTIARY = new RoadSection(4, 1, 6);

    public static RoadSection of(RoadType type) {
        return switch (type) {
            case PRIMARY -> PRIMARY;
            case SECONDARY -> SECONDARY;
            case TERTIARY -> TERTIARY;
            case NONE -> throw new IllegalArgumentException("NONE has no cross-section");
        };
    }

    public int total() {
        return 2 * (walk + kerb) + carriageway;
    }

    /** Left edge offset (inclusive) of the carriageway when centred. */
    public int carriagewayFrom() {
        return walk + kerb;
    }

    /** Right edge offset (inclusive) of the carriageway when centred. */
    public int carriagewayTo() {
        return walk + kerb + carriageway - 1;
    }

    public boolean isCentredEven() {
        return carriageway % 2 == 0 && total() % 2 == 0;
    }

    /**
     * The two middle cells of the carriageway, as a half-open range: {@code [first, first + 2)}.
     *
     * <p>Every tier has an even carriageway and a symmetric section, so its middle is always the
     * two blocks at offset 7 and 8 - the same cells for all three tiers, which is what keeps the
     * centre line of one piece aligned with the centre line of the next. Only
     * {@link #PRIMARY} actually paints it.
     */
    public int centreFrom() {
        return carriagewayFrom() + carriageway / 2 - 1;
    }

    /** Whether this tier paints its two middle cells as a centre line. */
    public boolean hasCentreLine() {
        return this.equals(PRIMARY);
    }
}
