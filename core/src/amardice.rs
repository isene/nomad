// Amar O6 dice engine for the amardice mobile app. Ported verbatim from the
// amar TUI's src/dice.rs (d6gaming.org canon) so phone, TUI, and wiki agree.
//
// O6: roll a d6. On 6, reroll +1 per 4/5/6, stop on 1/2/3. On 1, reroll -1 per
// 1/2/3, stop on 4/5/6. Two consecutive 6s anywhere -> Critical; two
// consecutive 1s -> Fumble (flags, not terminators; the cascade continues).
//
// Each exported roll takes a `seed` (Kotlin passes System.nanoTime()) so the
// functions stay pure and host-testable. Internal RNG is SplitMix64.

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Outcome {
    Normal,
    Critical,
    Fumble,
}

/// One O6 roll: the accumulated total, the crit/fumble flag, and the full die
/// sequence (including the terminator) so the table can show the trail.
#[derive(Debug, Clone, uniffi::Record)]
pub struct O6Roll {
    pub total: i32,
    pub outcome: Outcome,
    pub sequence: Vec<i32>,
}

/// One entry on a critical/fumble table.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TableHit {
    pub category: i32,
    pub category_name: String,
    pub entry: i32,
    pub description: String,
}

/// Critical/fumble table resolution. `recursive` is true when the trigger
/// category (Crit 6 / Fumble 1) fired, so `hits` holds two sub-rolls and an
/// experience mark is gained (crit) or lost (fumble).
#[derive(Debug, Clone, uniffi::Record)]
pub struct TableRoll {
    pub recursive: bool,
    pub hits: Vec<TableHit>,
}

/// A skill or combat roll: the O6 plus, on a crit/fumble, the table result.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RollResult {
    pub roll: O6Roll,
    pub table: Option<TableRoll>,
}

/// A fear check: O6 + Mental Fortitude vs the Fear DR, with the graded effect.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FearResult {
    pub roll: O6Roll,
    pub mental_fortitude: i32,
    pub total: i32,
    pub fear_dr: i32,
    pub success: bool,
    pub miss_by: i32,
    pub effect: String,
}

// ----------------------------------------------------------------- RNG (priv)

struct Rng {
    state: u64,
}

impl Rng {
    fn new(seed: u64) -> Self {
        let s = if seed == 0 { 0x9e3779b97f4a7c15 } else { seed };
        Self { state: s }
    }
    fn d6(&mut self) -> i32 {
        self.state = self.state.wrapping_add(0x9e3779b97f4a7c15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94d049bb133111eb);
        ((z >> 32) % 6 + 1) as i32
    }
}

// --------------------------------------------------------------- O6 mechanics

fn o6(rng: &mut Rng) -> O6Roll {
    let mut sequence = Vec::with_capacity(2);
    let first = rng.d6();
    sequence.push(first);
    if (2..=5).contains(&first) {
        return O6Roll { total: first, outcome: Outcome::Normal, sequence };
    }
    if first == 6 {
        let mut total = 6;
        let mut prev = 6;
        let mut outcome = Outcome::Normal;
        loop {
            let r = rng.d6();
            sequence.push(r);
            if prev == 6 && r == 6 {
                outcome = Outcome::Critical;
            }
            if (4..=6).contains(&r) {
                total += 1;
            } else {
                return O6Roll { total, outcome, sequence };
            }
            prev = r;
        }
    }
    // first == 1: down cascade.
    let mut total = 1;
    let mut prev = 1;
    let mut outcome = Outcome::Normal;
    loop {
        let r = rng.d6();
        sequence.push(r);
        if prev == 1 && r == 1 {
            outcome = Outcome::Fumble;
        }
        if (1..=3).contains(&r) {
            total -= 1;
        } else {
            return O6Roll { total, outcome, sequence };
        }
        prev = r;
    }
}

// ------------------------------------------------------------ Exported rolls

/// Plain d6 (1-6), no open-ending.
#[uniffi::export]
pub fn roll_d6(seed: u64) -> i32 {
    Rng::new(seed).d6()
}

/// Bare O6 roll (no table).
#[uniffi::export]
pub fn roll_o6(seed: u64) -> O6Roll {
    o6(&mut Rng::new(seed))
}

/// Skill roll: O6, plus the general-skill crit/fumble table on a crit/fumble.
#[uniffi::export]
pub fn roll_skill(seed: u64) -> RollResult {
    let mut rng = Rng::new(seed);
    let roll = o6(&mut rng);
    let table = match roll.outcome {
        Outcome::Critical => Some(roll_table(&mut rng, true, &SKILL_CRIT_CATS, &SKILL_CRIT_TABLE)),
        Outcome::Fumble => Some(roll_table(&mut rng, false, &SKILL_FUMBLE_CATS, &SKILL_FUMBLE_TABLE)),
        Outcome::Normal => None,
    };
    RollResult { roll, table }
}

/// Combat roll: O6, plus the combat crit/fumble table on a crit/fumble.
#[uniffi::export]
pub fn roll_combat(seed: u64) -> RollResult {
    let mut rng = Rng::new(seed);
    let roll = o6(&mut rng);
    let table = match roll.outcome {
        Outcome::Critical => Some(roll_table(&mut rng, true, &COMBAT_CRIT_CATS, &COMBAT_CRIT_TABLE)),
        Outcome::Fumble => Some(roll_table(&mut rng, false, &COMBAT_FUMBLE_CATS, &COMBAT_FUMBLE_TABLE)),
        Outcome::Normal => None,
    };
    RollResult { roll, table }
}

/// Fear check: (Mental Fortitude + O6) vs Fear DR, with graded effect.
/// A Fumble is always a critical failure (heart attack); a Critical always
/// masters the fear.
#[uniffi::export]
pub fn roll_fear(seed: u64, mental_fortitude: i32, fear_dr: i32) -> FearResult {
    let roll = o6(&mut Rng::new(seed));
    let total = roll.total + mental_fortitude;
    let miss_by = fear_dr - total;
    let (success, effect) = match roll.outcome {
        Outcome::Fumble => (
            false,
            "Critical failure: heart attack (Endurance check or fall unconscious)".to_string(),
        ),
        Outcome::Critical => (true, "Critical: you master the fear, no effect".to_string()),
        Outcome::Normal => {
            if miss_by <= 0 {
                (true, "Success: no effect".to_string())
            } else {
                let e = match miss_by {
                    1 => "Miss by 1: -1 to actions this round",
                    2 => "Miss by 2: -1 to actions for two rounds",
                    3 => "Miss by 3: -1 to actions for three rounds",
                    4 => "Miss by 4: frozen for 1 round",
                    _ => "Miss by 5+: flee in panic",
                };
                (false, e.to_string())
            }
        }
    };
    FearResult {
        roll,
        mental_fortitude,
        total,
        fear_dr,
        success,
        miss_by: miss_by.max(0),
        effect,
    }
}

// ----------------------------------------------------------- table machinery

/// Roll a category (1-6) then an entry (1-6). The trigger category (6 for
/// criticals, 1 for fumbles) means "roll twice, ignoring further triggers".
/// `is_crit` selects which trigger category and table indexing applies.
fn roll_table(
    rng: &mut Rng,
    is_crit: bool,
    cats: &[&str; 6],
    table: &[[&str; 6]; 5],
) -> TableRoll {
    let trigger = if is_crit { 6 } else { 1 };
    let cat = rng.d6();
    if cat == trigger {
        let mut hits = Vec::with_capacity(2);
        for _ in 0..2 {
            let mut c = rng.d6();
            while c == trigger {
                c = rng.d6();
            }
            let e = rng.d6();
            hits.push(entry(c, e, is_crit, cats, table));
        }
        return TableRoll { recursive: true, hits };
    }
    let e = rng.d6();
    TableRoll { recursive: false, hits: vec![entry(cat, e, is_crit, cats, table)] }
}

fn entry(cat: i32, e: i32, is_crit: bool, cats: &[&str; 6], table: &[[&str; 6]; 5]) -> TableHit {
    // Criticals: table holds cats 1-5 (cat 6 is recursive).
    // Fumbles:   table holds cats 2-6 (cat 1 is recursive).
    let (clamped, row) = if is_crit {
        let c = cat.clamp(1, 5);
        (c, c - 1)
    } else {
        let c = cat.clamp(2, 6);
        (c, c - 2)
    };
    let e_c = e.clamp(1, 6);
    let _ = clamped;
    TableHit {
        category: cat,
        category_name: cats[(cat as usize - 1).min(5)].to_string(),
        entry: e_c,
        description: table[row as usize][e_c as usize - 1].to_string(),
    }
}

// --------------------------------------------------------------- table data

// Combat tables: verbatim from the amar TUI / d6gaming.org/Combat.
const COMBAT_CRIT_CATS: [&str; 6] = [
    "Impression",
    "Side effect",
    "Increased effect",
    "Added effect",
    "Special",
    "Roll twice (no 6s) + 1 mark",
];
const COMBAT_FUMBLE_CATS: [&str; 6] = [
    "Roll twice (no 1s) - 1 mark",
    "Special",
    "Unwanted effect",
    "Stun effect",
    "Added effect",
    "Impression",
];
const COMBAT_CRIT_TABLE: [[&str; 6]; 5] = [
    [
        "Looks really cool",
        "Impressive: adjacent friends get +1 next round",
        "Very impressive: adjacent friends get +1 next D rounds",
        "Fearsome: foe rolls on Fear Table with +9 adjustment",
        "Awesome: foe rolls on Fear Table with +6 adjustment",
        "Wild: foe rolls on Fear Table with +3 adjustment",
    ],
    [
        "Opponent off balance: Status -1 next round",
        "Opponent confused: Status -3 next round",
        "Opponent stunned: Status -3 for 3 rounds",
        "Opponent staggered: Status -D for D rounds",
        "Opponent reeling: Status -O for O rounds",
        "Opponent shocked: Status -(O+3) for the rest of the fight",
    ],
    [
        "Good hit: +1 damage",
        "Tough hit: +3 damage",
        "Great hit: +(D+1) damage",
        "Greater hit: +(O+2) damage",
        "Power hit: double damage (after AP)",
        "Opportunity found: immediate free attack",
    ],
    [
        "Foe knocked down on failed Tumble DR 8",
        "Foe knocked down on failed Tumble DR 12",
        "Roll for disarming the opponent",
        "Damage also done to opponent's weapon",
        "Damage also done to opponent's weapon (double to weapon)",
        "Opponent loses equipment (GM's discretion)",
    ],
    [
        "Bleeding: -1 BP per minute",
        "Bleeding: -1 BP per round",
        "Muscle strained: opponent Status -3 until Medical Lore DR 8",
        "Disable special location (eye, finger): Medical Lore DR 8",
        "Disable special location: Medical Lore DR 12 to fix",
        "Opponent faints: Medical Lore DR 8 to awaken",
    ],
];
const COMBAT_FUMBLE_TABLE: [[&str; 6]; 5] = [
    // cat 2 - Special
    [
        "Lose next attack; opponent gets +10 to next attack",
        "Hit self",
        "Hit nearest friend",
        "Hit nearest friend, half damage",
        "Obstruct nearest friend: friend Status -3 next round",
        "Muscle strained: Status -3 until Medical Lore DR 8",
    ],
    // cat 3 - Unwanted effect
    [
        "Lose equipment (GM's discretion)",
        "Damage to own weapon",
        "Weapon stuck: Strength DR 10 to free",
        "Lose weapon: no attack until retrieved, -5 defense",
        "Fall on failed Tumble DR 12",
        "Fall on failed Tumble DR 8",
    ],
    // cat 4 - Stun effect
    [
        "Shocked: Status -(O+3) for rest of fight",
        "Reeling: Status -O for O rounds",
        "Staggered: Status -D for D rounds",
        "Stunned: Status -3 for 3 rounds",
        "Confused: Status -3 next round",
        "Off balance: Status -1 next round",
    ],
    // cat 5 - Added effect
    [
        "Very fatigued: Endurance -3 for rest of fight (min 1)",
        "Very tired: Strength -3 for rest of fight (min 1)",
        "Very dazed: Reaction Speed and Awareness -3 for rest of fight",
        "Fatigued: Endurance -1 for rest of fight (min 1)",
        "Tired: Strength -1 for rest of fight (min 1)",
        "Dazed: Reaction Speed and Awareness -1 for rest of fight",
    ],
    // cat 6 - Impression
    [
        "Terrible for morale: friends -1 to all rolls for next D rounds",
        "Very bad for morale: friends -1 to all rolls for rest of round",
        "Bad for morale: friends -1 to attack for rest of round",
        "You make a fool of yourself: laughter is heard",
        "Botched it: giggles are heard",
        "Awkward looking",
    ],
];

// Skill tables: the generic non-combat tables (d6gaming.org/The_Character).
const SKILL_CRIT_CATS: [&str; 6] = [
    "Impression",
    "Efficiency",
    "Increased effect",
    "Added effect",
    "Special",
    "Roll twice (no 6s) + 1 mark",
];
const SKILL_FUMBLE_CATS: [&str; 6] = [
    "Roll twice (no 1s) - 1 mark",
    "Setback",
    "Unwanted effect",
    "Personal cost",
    "Added effect",
    "Impression",
];
const SKILL_CRIT_TABLE: [[&str; 6]; 5] = [
    [
        "Clean work, onlookers notice",
        "Impressive: one ally +1 on next related roll",
        "Very impressive: allies +1 on related rolls for D rounds",
        "Inspiring: allies +3 on next related roll",
        "Renowned: +3 reaction from those who hear of it",
        "Legendary: lasting reputation boon (GM's discretion)",
    ],
    [
        "Brisk: task takes 3/4 the time",
        "Quick: half the time",
        "Swift: a quarter of the time",
        "Frugal: uses half the materials",
        "Effortless: no resources, or a free follow-up action",
        "Instant: a fraction of the time and minimal resources",
    ],
    [
        "Solid: clearly beat the DR",
        "Fine: one grade of quality better",
        "Excellent: effective result raised by +O",
        "Superior: markedly better (GM: one grade up)",
        "Masterful: double the magnitude or quality",
        "Flawless: best possible outcome",
    ],
    [
        "Learn something useful: +1 on next related attempt",
        "Spot an extra detail or opportunity (GM clue)",
        "Also helps an ally or the next task: +3 there",
        "Clear a secondary minor objective for free",
        "Create a reusable advantage (shortcut, sample)",
        "Windfall: an unexpected tangible bonus (GM's discretion)",
    ],
    [
        "Insight: +1 to this skill until next session",
        "Breakthrough: gain 1 experience mark",
        "Reputation: witnesses remember you favorably",
        "Keepsake: gain a useful by-product or token",
        "Teaching moment: an ally also gains a mark",
        "Epiphany: gain 1 mark and next attempt at +3",
    ],
];
const SKILL_FUMBLE_TABLE: [[&str; 6]; 5] = [
    // cat 2 - Setback
    [
        "Slow: task takes 1.5x the time",
        "Clumsy start: lose next action or restart the step",
        "Wasted effort: double the time",
        "Lost ground: undo the last increment of progress",
        "Stalled: start the task over from scratch",
        "Dead end: this approach will not work, find another",
    ],
    // cat 3 - Unwanted effect
    [
        "Scuff: minor cosmetic damage to a tool or the work",
        "Damage a tool or materials: -1 until repaired",
        "Tool jammed: fix-roll DR 10 to recover",
        "Ruin the materials: consumables spent for nothing",
        "Break your tool: needs repair or replacement",
        "Collateral mishap: damage something nearby (GM)",
    ],
    // cat 4 - Personal cost
    [
        "Off balance: -1 to actions next round",
        "Strained: -1 to this skill until you rest",
        "Tweaked: Status -3 until Medical Lore DR 8 or rest",
        "Rattled: -3 to related actions for D rounds",
        "Hurt: take D damage fitting the task",
        "Badly hurt: take O damage, -3 to actions for the scene",
    ],
    // cat 5 - Added effect
    [
        "Your slip hinders an ally: they are at -1",
        "It compounds: next related roll at -3",
        "False confidence: you believe you succeeded",
        "Waste a resource an ally was counting on",
        "Create a hazard others must work around (GM)",
        "Cascade: a second thing goes wrong",
    ],
    // cat 6 - Impression
    [
        "Awkward: it looks clumsy",
        "Botched: onlookers snicker",
        "You make a fool of yourself, laughter is heard",
        "Bad showing: allies -1 to related rolls this round",
        "Embarrassing: -3 reaction with witnesses",
        "Humiliating: word spreads (lasting reputation ding)",
    ],
];

#[cfg(test)]
mod tests {
    use super::*;

    // A queue-driven RNG would need access to the private struct; instead we
    // assert on the seeded engine's invariants, which is what ships.

    #[test]
    fn d6_in_range() {
        for seed in 1..2000u64 {
            let v = roll_d6(seed);
            assert!((1..=6).contains(&v), "d6 out of range: {}", v);
        }
    }

    #[test]
    fn o6_sequence_nonempty_and_consistent() {
        for seed in 1..3000u64 {
            let r = roll_o6(seed);
            assert!(!r.sequence.is_empty());
            // First die determines the cascade direction.
            let first = r.sequence[0];
            assert!((1..=6).contains(&first));
            if (2..=5).contains(&first) {
                assert_eq!(r.total, first);
                assert_eq!(r.outcome, Outcome::Normal);
            }
        }
    }

    #[test]
    fn skill_and_combat_tables_only_on_crit_or_fumble() {
        for seed in 1..4000u64 {
            for res in [roll_skill(seed), roll_combat(seed)] {
                match res.roll.outcome {
                    Outcome::Normal => assert!(res.table.is_none()),
                    _ => {
                        let t = res.table.expect("crit/fumble must carry a table");
                        assert!(!t.hits.is_empty());
                        for h in &t.hits {
                            assert!(!h.description.is_empty());
                            assert!((1..=6).contains(&h.entry));
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn skill_and_combat_descriptions_differ() {
        // Find a crit seed for each and confirm the tables are distinct data.
        // (Same seed yields the same O6 + same category/entry draw, so a crit
        // seed exercised through both paths must give different text unless the
        // entry text happens to coincide — assert on the category vocabulary.)
        let mut checked = false;
        for seed in 1..40000u64 {
            let s = roll_skill(seed);
            let c = roll_combat(seed);
            // Same seed => same O6 => same outcome + same category draw for both.
            if s.roll.outcome != Outcome::Critical {
                continue;
            }
            if let (Some(st), Some(ct)) = (&s.table, &c.table) {
                if !st.recursive && st.hits[0].category == 2 {
                    // Critical cat-2: skill is "Efficiency", combat is "Side effect".
                    assert_eq!(st.hits[0].category_name, "Efficiency");
                    assert_eq!(ct.hits[0].category_name, "Side effect");
                    assert_ne!(st.hits[0].description, ct.hits[0].description);
                    checked = true;
                    break;
                }
            }
        }
        assert!(checked, "expected to find a cat-2 critical to compare");
    }

    #[test]
    fn fear_grades() {
        // Force outcomes by scanning seeds for a Normal O6 of known total.
        // Easier: check the arithmetic for a Normal roll directly.
        for seed in 1..5000u64 {
            let f = roll_fear(seed, 5, 12);
            match f.roll.outcome {
                Outcome::Fumble => {
                    assert!(!f.success);
                    assert!(f.effect.contains("heart attack"));
                }
                Outcome::Critical => assert!(f.success),
                Outcome::Normal => {
                    assert_eq!(f.total, f.roll.total + 5);
                    if f.total >= 12 {
                        assert!(f.success);
                    } else {
                        assert!(!f.success);
                        assert_eq!(f.miss_by, 12 - f.total);
                    }
                }
            }
        }
    }
}
