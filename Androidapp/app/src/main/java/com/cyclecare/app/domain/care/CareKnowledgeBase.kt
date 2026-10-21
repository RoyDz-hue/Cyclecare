package com.cyclecare.app.domain.care

import com.cyclecare.app.data.model.CyclePhase

data class CareArticle(
    val id: String,
    val question: String,
    val topic: String,
    val whatText: String, // WHAT is happening
    val understandText: String, // UNDERSTAND physiological mechanism
    val careText: String, // CARE actionable steps
    val watchText: String, // WATCH symptoms to monitor
    val escalateText: String, // ESCALATE when to see doctor
    val relevantPhases: List<CyclePhase> = emptyList()
)

object CareKnowledgeBase {

    val articles = listOf(
        CareArticle(
            id = "tired_before_period",
            question = "Why am I so tired before my period?",
            topic = "Premenstrual Fatigue",
            whatText = "A noticeable decline in physical stamina and mental focus in the 3 to 7 days before menstruation.",
            understandText = "After ovulation, progesterone rises and then plummets alongside estrogen if conception didn't occur. This hormonal dive lowers brain serotonin and metabolic efficiency.",
            careText = "Consider magnesium-containing foods if they suit you, prioritize rest, and choose gentler movement if intense exercise feels difficult.",
            watchText = "Monitor whether fatigue lifts within 1-2 days of your period starting, and check for signs of daytime drowsiness impacting daily function.",
            escalateText = "Consult a physician if exhaustion is debilitating, doesn't improve after your period starts, or is accompanied by breathless episodes (possible anemia or thyroid imbalance).",
            relevantPhases = listOf(CyclePhase.LUTEAL)
        ),
        CareArticle(
            id = "discharge_changes",
            question = "What do changes in vaginal discharge mean?",
            topic = "Vaginal Health & Fluid",
            whatText = "Discharge naturally changes in volume, color, and texture throughout the menstrual cycle due to fluctuating estrogen levels.",
            understandText = "Post-period fluid is minimal/dry. Approaching ovulation, estrogen turns it creamy, then stretchy and clear ('egg white'). After ovulation, progesterone makes it thick and white.",
            careText = "Wear breathable cotton underwear, avoid douching or scented body washes, and drink fluids according to your usual needs. Some discharge changes can be normal, but a new or bothersome change deserves attention.",
            watchText = "Watch for sudden shifts: thick cottage-cheese texture, frothy greenish appearance, fishy odor, or accompanying itching and burning.",
            escalateText = "Seek clinical assessment if accompanied by pain, strong persistent odor, pelvic tenderness, or burning during urination or intimacy.",
            relevantPhases = listOf(CyclePhase.FOLLICULAR, CyclePhase.OVULATION, CyclePhase.LUTEAL)
        ),
        CareArticle(
            id = "ease_pelvic_cramps",
            question = "How to ease pelvic cramps and period pain?",
            topic = "Dysmenorrhea & Cramps",
            whatText = "Aching or throbbing contractions in the lower abdomen or pelvic basin right before and during menstruation.",
            understandText = "The uterine lining produces prostaglandins, chemical messengers that induce uterine muscle contractions to shed the endometrium. Higher prostaglandin levels equal more intense cramps.",
            careText = "Apply a warm heating pad or hot water bottle to the lower abdomen (heat may provide comfort for some people). Sip warm ginger or chamomile tea, and practice gentle pelvic rocking.",
            watchText = "Keep track of how long cramps last. Typical primary dysmenorrhea peaks on days 1-2 and recedes significantly by day 3.",
            escalateText = "Seek medical advice if pain is so severe you vomit or miss work, radiates sharply down your legs, or starts worsening after age 25 (possible endometriosis or fibroids).",
            relevantPhases = listOf(CyclePhase.MENSTRUAL)
        ),
        CareArticle(
            id = "urinary_burning_urgency",
            question = "What causes burning or urgency when urinating?",
            topic = "Urinary Health",
            whatText = "A stinging or burning sensation during urination, frequently accompanied by feeling an urgent need to go even when the bladder is empty.",
            understandText = "Often triggered when bacteria (commonly E. coli) enter the urethra and multiply in the bladder lining, causing acute mucosal inflammation (cystitis/UTI).",
            careText = "Drink fluids normally if you can, and avoid anything that seems to worsen irritation. Avoid caffeine, alcohol, and sugary drinks which irritate the bladder wall.",
            watchText = "Observe urine color, odor, and whether lower back or pelvic discomfort intensifies over the next 12 hours.",
            escalateText = "Consult a healthcare provider promptly. A clinician can decide whether testing or treatment is appropriate. Seek immediate urgent care if you experience fever, chills, or flank pain.",
            relevantPhases = listOf(CyclePhase.MENSTRUAL, CyclePhase.FOLLICULAR, CyclePhase.OVULATION, CyclePhase.LUTEAL)
        ),
        CareArticle(
            id = "headaches_before_period",
            question = "Why do I get headaches right before bleeding starts?",
            topic = "Hormonal Headaches",
            whatText = "Tension or throbbing headaches localized to the temples or forehead occurring 1 to 3 days before period onset.",
            understandText = "Directly triggered by the rapid withdrawal of circulating estrogen right before menstruation. Estrogen drops impact cranial blood vessel constriction and brain neurotransmitters.",
            careText = "Maintain steady hydration with electrolyte water. Eat regular meals to prevent blood sugar dips. Apply an ice pack to the neck and rest in a dim, quiet room.",
            watchText = "Note whether the headache responds to sleep, fluids, and gentle neck relaxation.",
            escalateText = "Consult a doctor if you experience neurological aura (visual flashes, numbness), sudden 'thunderclap' severity, or headaches unresponsive to treatment.",
            relevantPhases = listOf(CyclePhase.LUTEAL, CyclePhase.MENSTRUAL)
        ),
        CareArticle(
            id = "ovulation_spotting_pain",
            question = "Is mild spotting or twinges mid-cycle normal?",
            topic = "Ovulation & Mittelschmerz",
            whatText = "A brief light pink or brown speck of spotting, or a sharp one-sided twinge in the lower pelvic area roughly 2 weeks before your period.",
            understandText = "Known as Mittelschmerz ('middle pain'). As the follicle ruptures to release the mature egg, a tiny amount of fluid or follicular pressure causes mild local peritoneal irritation.",
            careText = "Relax with a warm cup of herbal tea. Mild twinges can have different explanations and do not confirm ovulation.",
            watchText = "Confirm that the sensation resolves within 24 to 48 hours and doesn't progress into acute distress.",
            escalateText = "Seek urgent care if pain becomes severe, shifts to persistent rigidity, or is accompanied by high fever or dizziness.",
            relevantPhases = listOf(CyclePhase.OVULATION)
        ),
        CareArticle(
            id = "breast_tenderness_cycle",
            question = "Why do my breasts feel swollen and tender before my period?",
            topic = "Cyclical Mastalgia",
            whatText = "Fullness, heaviness, or hypersensitivity in the breast tissue usually beginning after ovulation.",
            understandText = "Progesterone and estrogen stimulate milk duct proliferation and local fluid retention in breast lobules during the luteal phase.",
            careText = "Wear a supportive, wire-free cotton bra. Reduce sodium and excess caffeine, and consider evening primrose oil or warm compresses.",
            watchText = "Notice if tenderness completely clears within a few days of period onset.",
            escalateText = "Seek medical evaluation if you detect a distinct hard lump, localized skin dimpling, or nipple discharge.",
            relevantPhases = listOf(CyclePhase.LUTEAL)
        ),
        CareArticle(
            id = "bloating_luteal",
            question = "Why does bloating feel so uncomfortable in the second half of the cycle?",
            topic = "Luteal Bloating & Digestion",
            whatText = "A sensation of abdominal fullness, fluid retention, and sluggish digestion in the 7 to 10 days before bleeding.",
            understandText = "Elevated progesterone relaxes smooth muscle tissues throughout the digestive tract, slowing intestinal motility and increasing water reabsorption.",
            careText = "Incorporate potassium-rich foods (bananas, avocados, spinach), sip peppermint tea, and take short gentle walks after meals.",
            watchText = "Track if abdominal bloating subsides once menstruation begins.",
            escalateText = "Consult a physician if bloating is accompanied by severe abdominal distension, intractable vomiting, or inability to keep fluids down.",
            relevantPhases = listOf(CyclePhase.LUTEAL)
        ),
        CareArticle(
            id = "mood_swings_pms",
            question = "Why do irritability and mood swings surge before my period?",
            topic = "Premenstrual Emotional Health",
            whatText = "Heightened emotional sensitivity, anxiety, tearfulness, or sudden irritability appearing during the luteal window.",
            understandText = "The rapid decline of progesterone and allopregnanolone alters GABA and serotonin receptor signaling in the central nervous system.",
            careText = "Set aside protected downtime, practice 4-7-8 breathing, reduce refined sugars, and communicate your need for space with loved ones.",
            watchText = "Log emotional trends to verify symptoms consistently clear once bleeding starts.",
            escalateText = "Consult a licensed clinician if feelings of hopelessness, severe panic, or thoughts of self-harm emerge (possible PMDD).",
            relevantPhases = listOf(CyclePhase.LUTEAL)
        ),
        CareArticle(
            id = "acne_hormonal",
            question = "Why do breakouts appear around the jawline and chin?",
            topic = "Hormonal Skin Changes",
            whatText = "Deep, tender cystic bumps along the lower third of the face in the late luteal phase.",
            understandText = "As estrogen drops premenstrually, relative androgen ratios rise, stimulating sebaceous glands to produce thicker sebum that clogs pores.",
            careText = "Cleanse gently with salicylic acid or zinc-based cleansers, avoid picking or squeezing, and stay consistent with hydration.",
            watchText = "Observe if new breakouts cease forming once your cycle restarts.",
            escalateText = "Visit a dermatologist if breakouts leave permanent scarring or are accompanied by excess facial hair growth.",
            relevantPhases = listOf(CyclePhase.LUTEAL, CyclePhase.MENSTRUAL)
        ),
        CareArticle(
            id = "lower_back_pain_period",
            question = "What causes aching lower back pain during my period?",
            topic = "Referred Pelvic Pain",
            whatText = "Dull or throbbing ache radiating across the lower spine and sacrum during menstruation.",
            understandText = "Uterine contractions driven by prostaglandins trigger shared nerve pathways that refer pain signals directly into the lumbar and sacral plexus.",
            careText = "Use lumbar heat wraps, practice gentle cat-cow and child's pose stretches, and take warm Epsom salt baths.",
            watchText = "Notice if back discomfort recedes as menstrual flow slows down.",
            escalateText = "Consult a doctor if back pain is accompanied by numbness in the legs, bowel/bladder incontinence, or severe fever.",
            relevantPhases = listOf(CyclePhase.MENSTRUAL)
        ),
        CareArticle(
            id = "irregular_cycles_causes",
            question = "What factors can cause menstrual cycles to vary in length?",
            topic = "Cycle Irregularity",
            whatText = "Cycles that vary by more than 7 to 9 days from month to month, or range outside 21 to 45 days.",
            understandText = "Ovulation timing is sensitive to hypothalamic stress, cortisol surges, travel, rapid weight changes, thyroid disorders, and hormonal shifts.",
            careText = "Maintain regular sleep rhythms, ensure adequate caloric and micronutrient intake, and keep a consistent cycle journal.",
            watchText = "Record whether irregular cycles happen occasionally after illness or persist continuously over 3 or more cycles.",
            escalateText = "Consult an OB/GYN or endocrinologist if cycles are consistently absent for >90 days or remain unpredictable.",
            relevantPhases = listOf(CyclePhase.FOLLICULAR, CyclePhase.OVULATION, CyclePhase.LUTEAL, CyclePhase.MENSTRUAL)
        ),
        CareArticle(
            id = "spotting_mid_cycle",
            question = "When is spotting between periods something to observe?",
            topic = "Intermenstrual Spotting",
            whatText = "Light reddish or brownish staining on underwear or toilet tissue between expected menstrual periods.",
            understandText = "Can stem from minor mid-cycle estrogen dips during ovulation, cervical sensitivity after intercourse, implantation, or hormonal contraceptives.",
            careText = "Wear light pantyliners, note the exact cycle day and color, and avoid vaginal douching.",
            watchText = "Monitor whether spotting is a single light episode or recurs across multiple weeks.",
            escalateText = "Consult a physician if intermenstrual bleeding is heavy, occurs post-menopause, or follows pelvic trauma.",
            relevantPhases = listOf(CyclePhase.FOLLICULAR, CyclePhase.OVULATION, CyclePhase.LUTEAL)
        ),
        CareArticle(
            id = "nausea_period",
            question = "Why do I feel nauseous or have loose stools on day 1 of my period?",
            topic = "Gastrointestinal Prostaglandin Response",
            whatText = "Mild nausea, reduced appetite, or digestive upset during the first 24 to 48 hours of bleeding.",
            understandText = "Prostaglandins released by the uterus enter the bloodstream and stimulate smooth muscle contractions in the adjacent intestines.",
            careText = "Sip warm ginger tea, eat small bland meals (toast, rice, bananas), and keep a warm compress on the abdomen.",
            watchText = "Check that gastrointestinal symptoms ease by day 2 or 3 of menstruation.",
            escalateText = "Seek urgent care if you cannot keep fluids down for 24 hours, experience high fever, or see blood in stool.",
            relevantPhases = listOf(CyclePhase.MENSTRUAL)
        ),
        CareArticle(
            id = "sleep_disruption_cycle",
            question = "Why is it harder to fall asleep in the luteal phase?",
            topic = "Luteal Insomnia & Body Temperature",
            whatText = "Difficulty falling asleep, lighter sleep, or night sweats in the days leading up to menstruation.",
            understandText = "Post-ovulation progesterone elevates basal body temperature by ~0.3-0.5°C, interfering with the natural nocturnal body cooling needed for deep REM sleep.",
            careText = "Keep the bedroom cool (18-20°C), avoid screens 1 hour before bed, and practice gentle stretching or reading.",
            watchText = "Notice if refreshing, restful sleep returns once your period arrives and temperature drops.",
            escalateText = "Consult a healthcare provider if severe insomnia persists continuously across all cycle phases.",
            relevantPhases = listOf(CyclePhase.LUTEAL)
        ),
        CareArticle(
            id = "libido_cycle_changes",
            question = "How do desire and libido change across the cycle?",
            topic = "Hormonal Libido Rhythms",
            whatText = "Noticeable peaks in sexual desire mid-cycle, with quieter or introspective phases later in the cycle.",
            understandText = "Estrogen and testosterone surge right before ovulation, naturally boosting libido, lubrication, and vitality.",
            careText = "Honor your body's natural rhythm without self-judgment. Communication with partners fosters intimacy and understanding.",
            watchText = "Observe personal patterns in your journal without expecting a rigid calendar schedule.",
            escalateText = "Consult a doctor if intercourse is painful (dyspareunia) or accompanied by burning or pelvic spasm.",
            relevantPhases = listOf(CyclePhase.FOLLICULAR, CyclePhase.OVULATION)
        ),
        CareArticle(
            id = "anovulatory_cycle",
            question = "What is an anovulatory cycle?",
            topic = "Cycle Without Ovulation",
            whatText = "A cycle where uterine bleeding occurs without an egg being released by the ovaries.",
            understandText = "Occasional anovulatory cycles happen naturally during puberty, perimenopause, or periods of acute psychological stress and travel.",
            careText = "Focus on restorative nutrition, stress moderation, and consistent rest. Avoid overtraining during high-stress periods.",
            watchText = "Observe if your cycle returns to its typical rhythm in the following month.",
            escalateText = "Seek clinical assessment if you experience recurrent unpredictable bleeding or are actively trying to conceive without success.",
            relevantPhases = listOf(CyclePhase.FOLLICULAR, CyclePhase.LUTEAL)
        ),
        CareArticle(
            id = "pcos_general_awareness",
            question = "What are the common signs of Polycystic Ovary Syndrome (PCOS)?",
            topic = "PCOS Understanding",
            whatText = "A common endocrine condition characterized by irregular or missed periods, elevated androgens, and polycystic ovaries on ultrasound.",
            understandText = "Insulin resistance and altered LH/FSH signaling prevent regular follicular maturation and timely ovulation.",
            careText = "Emphasize balanced meals with fiber and protein to stabilize blood glucose, engage in consistent movement, and track symptoms.",
            watchText = "Document cycle intervals, skin changes, and hair patterns over several consecutive months.",
            escalateText = "Consult an endocrinologist or gynecologist for proper clinical evaluation, hormonal blood panels, and ultrasound assessment.",
            relevantPhases = listOf(CyclePhase.FOLLICULAR, CyclePhase.LUTEAL, CyclePhase.MENSTRUAL)
        ),
        CareArticle(
            id = "endometriosis_awareness",
            question = "What are the signs of Endometriosis?",
            topic = "Endometriosis Awareness",
            whatText = "A chronic condition where tissue similar to the lining of the uterus grows outside the uterus on ovaries, tubes, or bowel.",
            understandText = "These ectopic implants swell and bleed in response to monthly hormonal cycles, triggering chronic inflammation, adhesions, and nerve irritation.",
            careText = "Anti-inflammatory nutrition, pelvic floor physiotherapy, and warm hydrotherapy can offer complementary comfort.",
            watchText = "Notice if cramps are worsening year over year or persist outside of menstrual bleeding days.",
            escalateText = "Schedule a consultation with an endometriosis specialist if you experience deep pelvic pain during intimacy, urination, or bowel movements.",
            relevantPhases = listOf(CyclePhase.MENSTRUAL, CyclePhase.LUTEAL)
        ),
        CareArticle(
            id = "iron_deficiency_period",
            question = "How does heavy bleeding affect iron levels and energy?",
            topic = "Menstrual Blood Loss & Iron",
            whatText = "Persistent fatigue, pale skin, cold extremities, or dizziness during or after heavy menstrual periods.",
            understandText = "Substantial blood loss depletes red blood cells and ferritin (stored iron), reducing the oxygen-carrying capacity of hemoglobin.",
            careText = "Consume iron-rich foods (lentils, dark leafy greens, beans) paired with vitamin C to enhance intestinal absorption.",
            watchText = "Track the number of pads/tampons soaked per hour. Soaking through one or more per hour for consecutive hours is heavy.",
            escalateText = "See a healthcare professional for a complete blood count (CBC) and ferritin test if fatigue is chronic.",
            relevantPhases = listOf(CyclePhase.MENSTRUAL)
        ),
        CareArticle(
            id = "cervical_mucus_guide",
            question = "How do I observe and interpret cervical fluid changes?",
            topic = "Cervical Mucus Patterns",
            whatText = "Daily changes in sensation and appearance of fluid at the vaginal opening throughout the cycle.",
            understandText = "Cervical crypts respond to estrogen by producing clear, slippery, stretchy mucus that facilitates sperm transit near ovulation.",
            careText = "Observe fluid when using the restroom before urinating. Record sensation (dry, damp, creamy, slippery) consistently.",
            watchText = "Distinguish healthy fertile fluid from infection signals (curd-like clumping, foul odor, or itching).",
            escalateText = "Consult a clinician if accompanied by burning, unusual green/yellow coloration, or pelvic tenderness.",
            relevantPhases = listOf(CyclePhase.FOLLICULAR, CyclePhase.OVULATION)
        ),
        CareArticle(
            id = "basal_body_temp_insights",
            question = "What does tracking Basal Body Temperature (BBT) tell me?",
            topic = "Thermal Shift & Ovulation Confirmation",
            whatText = "Your lowest resting body temperature taken immediately upon waking before any physical activity.",
            understandText = "Progesterone produced by the corpus luteum after ovulation has a thermogenic effect, raising waking temperature by 0.2 to 0.5°C until menstruation.",
            careText = "Take temperature immediately upon waking with a two-decimal basal thermometer at the exact same time each morning.",
            watchText = "Look for a sustained temperature rise of at least 3 consecutive days above the previous 6 days to confirm ovulation has occurred.",
            escalateText = "Discuss with a fertility specialist or doctor if you do not observe a thermal shift over several tracked cycles.",
            relevantPhases = listOf(CyclePhase.FOLLICULAR, CyclePhase.OVULATION, CyclePhase.LUTEAL)
        )
    )

    fun search(query: String, currentPhase: CyclePhase? = null): List<CareArticle> {
        val q = query.lowercase().trim()
        val baseList = if (q.isBlank()) {
            articles
        } else {
            articles.filter {
                it.question.lowercase().contains(q) ||
                it.topic.lowercase().contains(q) ||
                it.whatText.lowercase().contains(q) ||
                it.careText.lowercase().contains(q)
            }
        }

        // Prioritize articles matching current cycle phase
        if (currentPhase != null) {
            return baseList.sortedByDescending { article ->
                if (article.relevantPhases.contains(currentPhase)) 1 else 0
            }
        }
        return baseList
    }
}
