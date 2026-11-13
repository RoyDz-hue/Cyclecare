//! Port of domain/care/CareKnowledgeBase.kt (main@31b88f5): the 22 static
//! care articles verbatim + the free-form search. Article data and the
//! stopword list were generated from the Kotlin source programmatically (no
//! manual transcription). Search semantics locked by KBParity probe
//! (parity/ai, results_kb.tsv, 17 rows executed against the verbatim Kotlin):
//! lowercase+trim, Regex("[a-z]+") tokens with length>2 not in stopwords,
//! minScore 2 when >=2 tokens else 1, score = count of tokens that are a
//! SUBSTRING of the lowercased seven-field haystack, stable descending sort,
//! then a stable phase-priority re-sort (1/0) when a phase is supplied.

use crate::cycle_math::CyclePhase;
use crate::kotlin_text::kotlin_trim;

#[derive(Debug, Clone, PartialEq)]
pub struct CareArticle {
    pub id: &'static str,
    pub question: &'static str,
    pub topic: &'static str,
    pub what_text: &'static str,
    pub understand_text: &'static str,
    pub care_text: &'static str,
    pub watch_text: &'static str,
    pub escalate_text: &'static str,
    pub relevant_phases: &'static [CyclePhase],
}

const SEARCH_STOPWORDS: &[&str] = &[
"the", "and", "for", "with", "you", "your", "yours", "are", "was", "were", "have", "has", "had", "how", "can", "could", "should", "would", "what", "why", "when", "which", "where", "who", "does", "doing", "done", "will", "about", "tell", "explain", "please", "this", "that", "these", "those", "from", "into", "onto", "out", "off", "not", "any", "some", "get", "got", "make", "feel", "feeling", "really", "very", "much", "many", "way", "ways"
];

pub const ARTICLES: &[CareArticle] = &[

    CareArticle {
        id: "tired_before_period",
        question: "Why am I so tired before my period?",
        topic: "Premenstrual Fatigue",
        what_text: "A noticeable decline in physical stamina and mental focus in the 3 to 7 days before menstruation.",
        understand_text: "After ovulation, progesterone rises and then plummets alongside estrogen if conception didn't occur. This hormonal dive lowers brain serotonin and metabolic efficiency.",
        care_text: "Consider magnesium-containing foods if they suit you, prioritize rest, and choose gentler movement if intense exercise feels difficult.",
        watch_text: "Monitor whether fatigue lifts within 1-2 days of your period starting, and check for signs of daytime drowsiness impacting daily function.",
        escalate_text: "Consult a physician if exhaustion is debilitating, doesn't improve after your period starts, or is accompanied by breathless episodes (possible anemia or thyroid imbalance).",
        relevant_phases: &[CyclePhase::Luteal],
    },
    CareArticle {
        id: "discharge_changes",
        question: "What do changes in vaginal discharge mean?",
        topic: "Vaginal Health & Fluid",
        what_text: "Discharge naturally changes in volume, color, and texture throughout the menstrual cycle due to fluctuating estrogen levels.",
        understand_text: "Post-period fluid is minimal/dry. Approaching ovulation, estrogen turns it creamy, then stretchy and clear ('egg white'). After ovulation, progesterone makes it thick and white.",
        care_text: "Wear breathable cotton underwear, avoid douching or scented body washes, and drink fluids according to your usual needs. Some discharge changes can be normal, but a new or bothersome change deserves attention.",
        watch_text: "Watch for sudden shifts: thick cottage-cheese texture, frothy greenish appearance, fishy odor, or accompanying itching and burning.",
        escalate_text: "Seek clinical assessment if accompanied by pain, strong persistent odor, pelvic tenderness, or burning during urination or intimacy.",
        relevant_phases: &[CyclePhase::Follicular, CyclePhase::Ovulation, CyclePhase::Luteal],
    },
    CareArticle {
        id: "ease_pelvic_cramps",
        question: "How to ease pelvic cramps and period pain?",
        topic: "Dysmenorrhea & Cramps",
        what_text: "Aching or throbbing contractions in the lower abdomen or pelvic basin right before and during menstruation.",
        understand_text: "The uterine lining produces prostaglandins, chemical messengers that induce uterine muscle contractions to shed the endometrium. Higher prostaglandin levels equal more intense cramps.",
        care_text: "Apply a warm heating pad or hot water bottle to the lower abdomen (heat may provide comfort for some people). Sip warm ginger or chamomile tea, and practice gentle pelvic rocking.",
        watch_text: "Keep track of how long cramps last. Typical primary dysmenorrhea peaks on days 1-2 and recedes significantly by day 3.",
        escalate_text: "Seek medical advice if pain is so severe you vomit or miss work, radiates sharply down your legs, or starts worsening after age 25 (possible endometriosis or fibroids).",
        relevant_phases: &[CyclePhase::Menstrual],
    },
    CareArticle {
        id: "urinary_burning_urgency",
        question: "What causes burning or urgency when urinating?",
        topic: "Urinary Health",
        what_text: "A stinging or burning sensation during urination, frequently accompanied by feeling an urgent need to go even when the bladder is empty.",
        understand_text: "Often triggered when bacteria (commonly E. coli) enter the urethra and multiply in the bladder lining, causing acute mucosal inflammation (cystitis/UTI).",
        care_text: "Drink fluids normally if you can, and avoid anything that seems to worsen irritation. Avoid caffeine, alcohol, and sugary drinks which irritate the bladder wall.",
        watch_text: "Observe urine color, odor, and whether lower back or pelvic discomfort intensifies over the next 12 hours.",
        escalate_text: "Consult a healthcare provider promptly. A clinician can decide whether testing or treatment is appropriate. Seek immediate urgent care if you experience fever, chills, or flank pain.",
        relevant_phases: &[CyclePhase::Menstrual, CyclePhase::Follicular, CyclePhase::Ovulation, CyclePhase::Luteal],
    },
    CareArticle {
        id: "headaches_before_period",
        question: "Why do I get headaches right before bleeding starts?",
        topic: "Hormonal Headaches",
        what_text: "Tension or throbbing headaches localized to the temples or forehead occurring 1 to 3 days before period onset.",
        understand_text: "Directly triggered by the rapid withdrawal of circulating estrogen right before menstruation. Estrogen drops impact cranial blood vessel constriction and brain neurotransmitters.",
        care_text: "Maintain steady hydration with electrolyte water. Eat regular meals to prevent blood sugar dips. Apply an ice pack to the neck and rest in a dim, quiet room.",
        watch_text: "Note whether the headache responds to sleep, fluids, and gentle neck relaxation.",
        escalate_text: "Consult a doctor if you experience neurological aura (visual flashes, numbness), sudden 'thunderclap' severity, or headaches unresponsive to treatment.",
        relevant_phases: &[CyclePhase::Luteal, CyclePhase::Menstrual],
    },
    CareArticle {
        id: "ovulation_spotting_pain",
        question: "Is mild spotting or twinges mid-cycle normal?",
        topic: "Ovulation & Mittelschmerz",
        what_text: "A brief light pink or brown speck of spotting, or a sharp one-sided twinge in the lower pelvic area roughly 2 weeks before your period.",
        understand_text: "Known as Mittelschmerz ('middle pain'). As the follicle ruptures to release the mature egg, a tiny amount of fluid or follicular pressure causes mild local peritoneal irritation.",
        care_text: "Relax with a warm cup of herbal tea. Mild twinges can have different explanations and do not confirm ovulation.",
        watch_text: "Confirm that the sensation resolves within 24 to 48 hours and doesn't progress into acute distress.",
        escalate_text: "Seek urgent care if pain becomes severe, shifts to persistent rigidity, or is accompanied by high fever or dizziness.",
        relevant_phases: &[CyclePhase::Ovulation],
    },
    CareArticle {
        id: "breast_tenderness_cycle",
        question: "Why do my breasts feel swollen and tender before my period?",
        topic: "Cyclical Mastalgia",
        what_text: "Fullness, heaviness, or hypersensitivity in the breast tissue usually beginning after ovulation.",
        understand_text: "Progesterone and estrogen stimulate milk duct proliferation and local fluid retention in breast lobules during the luteal phase.",
        care_text: "Wear a supportive, wire-free cotton bra. Reduce sodium and excess caffeine, and consider evening primrose oil or warm compresses.",
        watch_text: "Notice if tenderness completely clears within a few days of period onset.",
        escalate_text: "Seek medical evaluation if you detect a distinct hard lump, localized skin dimpling, or nipple discharge.",
        relevant_phases: &[CyclePhase::Luteal],
    },
    CareArticle {
        id: "bloating_luteal",
        question: "Why does bloating feel so uncomfortable in the second half of the cycle?",
        topic: "Luteal Bloating & Digestion",
        what_text: "A sensation of abdominal fullness, fluid retention, and sluggish digestion in the 7 to 10 days before bleeding.",
        understand_text: "Elevated progesterone relaxes smooth muscle tissues throughout the digestive tract, slowing intestinal motility and increasing water reabsorption.",
        care_text: "Incorporate potassium-rich foods (bananas, avocados, spinach), sip peppermint tea, and take short gentle walks after meals.",
        watch_text: "Track if abdominal bloating subsides once menstruation begins.",
        escalate_text: "Consult a physician if bloating is accompanied by severe abdominal distension, intractable vomiting, or inability to keep fluids down.",
        relevant_phases: &[CyclePhase::Luteal],
    },
    CareArticle {
        id: "mood_swings_pms",
        question: "Why do irritability and mood swings surge before my period?",
        topic: "Premenstrual Emotional Health",
        what_text: "Heightened emotional sensitivity, anxiety, tearfulness, or sudden irritability appearing during the luteal window.",
        understand_text: "The rapid decline of progesterone and allopregnanolone alters GABA and serotonin receptor signaling in the central nervous system.",
        care_text: "Set aside protected downtime, practice 4-7-8 breathing, reduce refined sugars, and communicate your need for space with loved ones.",
        watch_text: "Log emotional trends to verify symptoms consistently clear once bleeding starts.",
        escalate_text: "Consult a licensed clinician if feelings of hopelessness, severe panic, or thoughts of self-harm emerge (possible PMDD).",
        relevant_phases: &[CyclePhase::Luteal],
    },
    CareArticle {
        id: "acne_hormonal",
        question: "Why do breakouts appear around the jawline and chin?",
        topic: "Hormonal Skin Changes",
        what_text: "Deep, tender cystic bumps along the lower third of the face in the late luteal phase.",
        understand_text: "As estrogen drops premenstrually, relative androgen ratios rise, stimulating sebaceous glands to produce thicker sebum that clogs pores.",
        care_text: "Cleanse gently with salicylic acid or zinc-based cleansers, avoid picking or squeezing, and stay consistent with hydration.",
        watch_text: "Observe if new breakouts cease forming once your cycle restarts.",
        escalate_text: "Visit a dermatologist if breakouts leave permanent scarring or are accompanied by excess facial hair growth.",
        relevant_phases: &[CyclePhase::Luteal, CyclePhase::Menstrual],
    },
    CareArticle {
        id: "lower_back_pain_period",
        question: "What causes aching lower back pain during my period?",
        topic: "Referred Pelvic Pain",
        what_text: "Dull or throbbing ache radiating across the lower spine and sacrum during menstruation.",
        understand_text: "Uterine contractions driven by prostaglandins trigger shared nerve pathways that refer pain signals directly into the lumbar and sacral plexus.",
        care_text: "Use lumbar heat wraps, practice gentle cat-cow and child's pose stretches, and take warm Epsom salt baths.",
        watch_text: "Notice if back discomfort recedes as menstrual flow slows down.",
        escalate_text: "Consult a doctor if back pain is accompanied by numbness in the legs, bowel/bladder incontinence, or severe fever.",
        relevant_phases: &[CyclePhase::Menstrual],
    },
    CareArticle {
        id: "irregular_cycles_causes",
        question: "What factors can cause menstrual cycles to vary in length?",
        topic: "Cycle Irregularity",
        what_text: "Cycles that vary by more than 7 to 9 days from month to month, or range outside 21 to 45 days.",
        understand_text: "Ovulation timing is sensitive to hypothalamic stress, cortisol surges, travel, rapid weight changes, thyroid disorders, and hormonal shifts.",
        care_text: "Maintain regular sleep rhythms, ensure adequate caloric and micronutrient intake, and keep a consistent cycle journal.",
        watch_text: "Record whether irregular cycles happen occasionally after illness or persist continuously over 3 or more cycles.",
        escalate_text: "Consult an OB/GYN or endocrinologist if cycles are consistently absent for >90 days or remain unpredictable.",
        relevant_phases: &[CyclePhase::Follicular, CyclePhase::Ovulation, CyclePhase::Luteal, CyclePhase::Menstrual],
    },
    CareArticle {
        id: "spotting_mid_cycle",
        question: "When is spotting between periods something to observe?",
        topic: "Intermenstrual Spotting",
        what_text: "Light reddish or brownish staining on underwear or toilet tissue between expected menstrual periods.",
        understand_text: "Can stem from minor mid-cycle estrogen dips during ovulation, cervical sensitivity after intercourse, implantation, or hormonal contraceptives.",
        care_text: "Wear light pantyliners, note the exact cycle day and color, and avoid vaginal douching.",
        watch_text: "Monitor whether spotting is a single light episode or recurs across multiple weeks.",
        escalate_text: "Consult a physician if intermenstrual bleeding is heavy, occurs post-menopause, or follows pelvic trauma.",
        relevant_phases: &[CyclePhase::Follicular, CyclePhase::Ovulation, CyclePhase::Luteal],
    },
    CareArticle {
        id: "nausea_period",
        question: "Why do I feel nauseous or have loose stools on day 1 of my period?",
        topic: "Gastrointestinal Prostaglandin Response",
        what_text: "Mild nausea, reduced appetite, or digestive upset during the first 24 to 48 hours of bleeding.",
        understand_text: "Prostaglandins released by the uterus enter the bloodstream and stimulate smooth muscle contractions in the adjacent intestines.",
        care_text: "Sip warm ginger tea, eat small bland meals (toast, rice, bananas), and keep a warm compress on the abdomen.",
        watch_text: "Check that gastrointestinal symptoms ease by day 2 or 3 of menstruation.",
        escalate_text: "Seek urgent care if you cannot keep fluids down for 24 hours, experience high fever, or see blood in stool.",
        relevant_phases: &[CyclePhase::Menstrual],
    },
    CareArticle {
        id: "sleep_disruption_cycle",
        question: "Why is it harder to fall asleep in the luteal phase?",
        topic: "Luteal Insomnia & Body Temperature",
        what_text: "Difficulty falling asleep, lighter sleep, or night sweats in the days leading up to menstruation.",
        understand_text: "Post-ovulation progesterone elevates basal body temperature by ~0.3-0.5°C, interfering with the natural nocturnal body cooling needed for deep REM sleep.",
        care_text: "Keep the bedroom cool (18-20°C), avoid screens 1 hour before bed, and practice gentle stretching or reading.",
        watch_text: "Notice if refreshing, restful sleep returns once your period arrives and temperature drops.",
        escalate_text: "Consult a healthcare provider if severe insomnia persists continuously across all cycle phases.",
        relevant_phases: &[CyclePhase::Luteal],
    },
    CareArticle {
        id: "libido_cycle_changes",
        question: "How do desire and libido change across the cycle?",
        topic: "Hormonal Libido Rhythms",
        what_text: "Noticeable peaks in sexual desire mid-cycle, with quieter or introspective phases later in the cycle.",
        understand_text: "Estrogen and testosterone surge right before ovulation, naturally boosting libido, lubrication, and vitality.",
        care_text: "Honor your body's natural rhythm without self-judgment. Communication with partners fosters intimacy and understanding.",
        watch_text: "Observe personal patterns in your journal without expecting a rigid calendar schedule.",
        escalate_text: "Consult a doctor if intercourse is painful (dyspareunia) or accompanied by burning or pelvic spasm.",
        relevant_phases: &[CyclePhase::Follicular, CyclePhase::Ovulation],
    },
    CareArticle {
        id: "anovulatory_cycle",
        question: "What is an anovulatory cycle?",
        topic: "Cycle Without Ovulation",
        what_text: "A cycle where uterine bleeding occurs without an egg being released by the ovaries.",
        understand_text: "Occasional anovulatory cycles happen naturally during puberty, perimenopause, or periods of acute psychological stress and travel.",
        care_text: "Focus on restorative nutrition, stress moderation, and consistent rest. Avoid overtraining during high-stress periods.",
        watch_text: "Observe if your cycle returns to its typical rhythm in the following month.",
        escalate_text: "Seek clinical assessment if you experience recurrent unpredictable bleeding or are actively trying to conceive without success.",
        relevant_phases: &[CyclePhase::Follicular, CyclePhase::Luteal],
    },
    CareArticle {
        id: "pcos_general_awareness",
        question: "What are the common signs of Polycystic Ovary Syndrome (PCOS)?",
        topic: "PCOS Understanding",
        what_text: "A common endocrine condition characterized by irregular or missed periods, elevated androgens, and polycystic ovaries on ultrasound.",
        understand_text: "Insulin resistance and altered LH/FSH signaling prevent regular follicular maturation and timely ovulation.",
        care_text: "Emphasize balanced meals with fiber and protein to stabilize blood glucose, engage in consistent movement, and track symptoms.",
        watch_text: "Document cycle intervals, skin changes, and hair patterns over several consecutive months.",
        escalate_text: "Consult an endocrinologist or gynecologist for proper clinical evaluation, hormonal blood panels, and ultrasound assessment.",
        relevant_phases: &[CyclePhase::Follicular, CyclePhase::Luteal, CyclePhase::Menstrual],
    },
    CareArticle {
        id: "endometriosis_awareness",
        question: "What are the signs of Endometriosis?",
        topic: "Endometriosis Awareness",
        what_text: "A chronic condition where tissue similar to the lining of the uterus grows outside the uterus on ovaries, tubes, or bowel.",
        understand_text: "These ectopic implants swell and bleed in response to monthly hormonal cycles, triggering chronic inflammation, adhesions, and nerve irritation.",
        care_text: "Anti-inflammatory nutrition, pelvic floor physiotherapy, and warm hydrotherapy can offer complementary comfort.",
        watch_text: "Notice if cramps are worsening year over year or persist outside of menstrual bleeding days.",
        escalate_text: "Schedule a consultation with an endometriosis specialist if you experience deep pelvic pain during intimacy, urination, or bowel movements.",
        relevant_phases: &[CyclePhase::Menstrual, CyclePhase::Luteal],
    },
    CareArticle {
        id: "iron_deficiency_period",
        question: "How does heavy bleeding affect iron levels and energy?",
        topic: "Menstrual Blood Loss & Iron",
        what_text: "Persistent fatigue, pale skin, cold extremities, or dizziness during or after heavy menstrual periods.",
        understand_text: "Substantial blood loss depletes red blood cells and ferritin (stored iron), reducing the oxygen-carrying capacity of hemoglobin.",
        care_text: "Consume iron-rich foods (lentils, dark leafy greens, beans) paired with vitamin C to enhance intestinal absorption.",
        watch_text: "Track the number of pads/tampons soaked per hour. Soaking through one or more per hour for consecutive hours is heavy.",
        escalate_text: "See a healthcare professional for a complete blood count (CBC) and ferritin test if fatigue is chronic.",
        relevant_phases: &[CyclePhase::Menstrual],
    },
    CareArticle {
        id: "cervical_mucus_guide",
        question: "How do I observe and interpret cervical fluid changes?",
        topic: "Cervical Mucus Patterns",
        what_text: "Daily changes in sensation and appearance of fluid at the vaginal opening throughout the cycle.",
        understand_text: "Cervical crypts respond to estrogen by producing clear, slippery, stretchy mucus that facilitates sperm transit near ovulation.",
        care_text: "Observe fluid when using the restroom before urinating. Record sensation (dry, damp, creamy, slippery) consistently.",
        watch_text: "Distinguish healthy fertile fluid from infection signals (curd-like clumping, foul odor, or itching).",
        escalate_text: "Consult a clinician if accompanied by burning, unusual green/yellow coloration, or pelvic tenderness.",
        relevant_phases: &[CyclePhase::Follicular, CyclePhase::Ovulation],
    },
    CareArticle {
        id: "basal_body_temp_insights",
        question: "What does tracking Basal Body Temperature (BBT) tell me?",
        topic: "Thermal Shift & Ovulation Confirmation",
        what_text: "Your lowest resting body temperature taken immediately upon waking before any physical activity.",
        understand_text: "Progesterone produced by the corpus luteum after ovulation has a thermogenic effect, raising waking temperature by 0.2 to 0.5°C until menstruation.",
        care_text: "Take temperature immediately upon waking with a two-decimal basal thermometer at the exact same time each morning.",
        watch_text: "Look for a sustained temperature rise of at least 3 consecutive days above the previous 6 days to confirm ovulation has occurred.",
        escalate_text: "Discuss with a fertility specialist or doctor if you do not observe a thermal shift over several tracked cycles.",
        relevant_phases: &[CyclePhase::Follicular, CyclePhase::Ovulation, CyclePhase::Luteal],
    },
];

fn search_tokens(q: &str) -> Vec<String> {
    // Regex("[a-z]+").findAll on the already-lowercased query.
    let mut tokens = Vec::new();
    let mut cur = String::new();
    for c in q.chars() {
        if c.is_ascii_lowercase() {
            cur.push(c);
        } else if !cur.is_empty() {
            if cur.len() > 2 && !SEARCH_STOPWORDS.contains(&cur.as_str()) {
                tokens.push(std::mem::take(&mut cur));
            } else {
                cur.clear();
            }
        }
    }
    if cur.len() > 2 && !SEARCH_STOPWORDS.contains(&cur.as_str()) {
        tokens.push(cur);
    }
    tokens
}

/// search(query, currentPhase): returns article references (static data).
pub fn search(query: &str, current_phase: Option<CyclePhase>) -> Vec<&'static CareArticle> {
    let q = kotlin_trim(query).to_lowercase();
    let base_list: Vec<&'static CareArticle> = if q.is_empty() {
        ARTICLES.iter().collect()
    } else {
        let tokens = search_tokens(&q);
        if tokens.is_empty() {
            ARTICLES
                .iter()
                .filter(|a| {
                    a.question.to_lowercase().contains(&q)
                        || a.topic.to_lowercase().contains(&q)
                        || a.what_text.to_lowercase().contains(&q)
                        || a.care_text.to_lowercase().contains(&q)
                })
                .collect()
        } else {
            let min_score = if tokens.len() >= 2 { 2 } else { 1 };
            let mut scored: Vec<(&'static CareArticle, usize)> = ARTICLES
                .iter()
                .map(|a| {
                    let haystack = format!(
                        "{} {} {} {} {} {} {}",
                        a.question, a.topic, a.what_text, a.understand_text,
                        a.care_text, a.watch_text, a.escalate_text
                    )
                    .to_lowercase();
                    let score = tokens.iter().filter(|t| haystack.contains(t.as_str())).count();
                    (a, score)
                })
                .filter(|(_, s)| *s >= min_score)
                .collect();
            // Kotlin sortedByDescending is stable.
            scored.sort_by(|a, b| b.1.cmp(&a.1));
            scored.into_iter().map(|(a, _)| a).collect()
        }
    };
    if let Some(phase) = current_phase {
        let mut ranked: Vec<(usize, &'static CareArticle)> = base_list.into_iter().enumerate()
            .map(|(i, a)| (i, a)).collect();
        // Stable re-sort: phase-relevant first, original order preserved.
        ranked.sort_by(|(ia, a), (ib, b)| {
            let ra = if a.relevant_phases.contains(&phase) { 0 } else { 1 };
            let rb = if b.relevant_phases.contains(&phase) { 0 } else { 1 };
            ra.cmp(&rb).then(ia.cmp(ib))
        });
        return ranked.into_iter().map(|(_, a)| a).collect();
    }
    base_list
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(r: Vec<&CareArticle>) -> Vec<&'static str> {
        r.into_iter().map(|a| a.id).collect()
    }

    #[test]
    fn article_data_integrity() {
        assert_eq!(ARTICLES.len(), 22);
        assert_eq!(ARTICLES[0].id, "tired_before_period");
        assert_eq!(ARTICLES[21].id, "basal_body_temp_insights");
        assert_eq!(SEARCH_STOPWORDS.len(), 55);
    }

    /// Every row locked to results_kb.tsv (verbatim Kotlin, executed).
    #[test]
    fn search_corpus_locked_to_jvm() {
        let all: Vec<&str> = ARTICLES.iter().map(|a| a.id).collect();
        assert_eq!(ids(search("", None)), all); // q01
        assert_eq!(ids(search("   ", None)), all); // q02

        assert_eq!(ids(search("Why am I so tired before my period?", None)), vec!["tired_before_period", "ease_pelvic_cramps", "headaches_before_period", "ovulation_spotting_pain", "breast_tenderness_cycle", "mood_swings_pms", "sleep_disruption_cycle"]); // q03
        assert_eq!(ids(search("cramps", None)), vec!["ease_pelvic_cramps", "endometriosis_awareness"]); // q04
        assert_eq!(ids(search("the and for", None)), Vec::<&str>::new()); // q05
        assert_eq!(ids(search("PCOS", None)), vec!["pcos_general_awareness"]); // q06
        assert_eq!(ids(search("tired", Some(CyclePhase::Luteal))), vec!["tired_before_period"]); // q07
        assert_eq!(ids(search("tired", Some(CyclePhase::Menstrual))), vec!["tired_before_period"]); // q08
        assert_eq!(ids(search("mood swings", None)), vec!["mood_swings_pms"]); // q09
        assert_eq!(ids(search("xy", None)), Vec::<&str>::new()); // q10
        assert_eq!(ids(search("How to ease pelvic cramps and period pain?", None)), vec!["ease_pelvic_cramps", "ovulation_spotting_pain", "discharge_changes", "lower_back_pain_period", "endometriosis_awareness", "urinary_burning_urgency", "spotting_mid_cycle", "nausea_period", "libido_cycle_changes", "anovulatory_cycle"]); // q11
        assert_eq!(ids(search("spotting between periods", None)), vec!["spotting_mid_cycle"]); // q12
        assert_eq!(ids(search("CRAMPS", Some(CyclePhase::Menstrual))), vec!["ease_pelvic_cramps", "endometriosis_awareness"]); // q13
        assert_eq!(ids(search("sleep", None)), vec!["headaches_before_period", "irregular_cycles_causes", "sleep_disruption_cycle"]); // q14
        assert_eq!(ids(search("iron fatigue energy", None)), vec!["iron_deficiency_period"]); // q15
        assert_eq!(ids(search("a", None)), all); // q16
        assert_eq!(ids(search("period", None)), vec!["tired_before_period", "discharge_changes", "ease_pelvic_cramps", "headaches_before_period", "ovulation_spotting_pain", "breast_tenderness_cycle", "mood_swings_pms", "lower_back_pain_period", "spotting_mid_cycle", "nausea_period", "sleep_disruption_cycle", "anovulatory_cycle", "pcos_general_awareness", "iron_deficiency_period"]); // q17
    }
}
