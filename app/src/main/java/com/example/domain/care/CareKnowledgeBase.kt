package com.example.domain.care

data class CareArticle(
    val id: String,
    val question: String,
    val topic: String,
    val whatText: String, // WHAT is happening
    val understandText: String, // UNDERSTAND physiological mechanism
    val careText: String, // CARE actionable steps
    val watchText: String, // WATCH symptoms to monitor
    val escalateText: String // ESCALATE when to see doctor
)

object CareKnowledgeBase {

    val articles = listOf(
        CareArticle(
            id = "tired_before_period",
            question = "Why am I so tired before my period?",
            topic = "Premenstrual Fatigue",
            whatText = "A noticeable decline in physical stamina and mental focus in the 3 to 7 days before menstruation.",
            understandText = "After ovulation, progesterone rises and then plummets alongside estrogen if conception didn't occur. This hormonal dive lowers brain serotonin and metabolic efficiency.",
            careText = "Increase magnesium intake (pumpkin seeds, dark chocolate, spinach), prioritize 8 hours of restorative sleep, and swap high-intensity workouts for restorative walks or yoga.",
            watchText = "Monitor whether fatigue lifts within 1-2 days of your period starting, and check for signs of daytime drowsiness impacting daily function.",
            escalateText = "Consult a physician if exhaustion is debilitating, doesn't improve after your period starts, or is accompanied by breathless episodes (possible anemia or thyroid imbalance)."
        ),
        CareArticle(
            id = "discharge_changes",
            question = "What do changes in vaginal discharge mean?",
            topic = "Vaginal Health & Fluid",
            whatText = "Discharge naturally changes in volume, color, and texture throughout the menstrual cycle due to fluctuating estrogen levels.",
            understandText = "Post-period fluid is minimal/dry. Approaching ovulation, estrogen turns it creamy, then stretchy and clear ('egg white'). After ovulation, progesterone makes it thick and white.",
            careText = "Wear breathable cotton underwear, avoid douching or scented body washes, and stay well hydrated. Normal discharge is an active protective self-cleaning mechanism.",
            watchText = "Watch for sudden shifts: thick cottage-cheese texture, frothy greenish appearance, fishy odor, or accompanying itching and burning.",
            escalateText = "Seek clinical assessment if accompanied by pain, strong persistent odor, pelvic tenderness, or burning during urination or intimacy."
        ),
        CareArticle(
            id = "ease_pelvic_cramps",
            question = "How to ease pelvic cramps and period pain?",
            topic = "Dysmenorrhea & Cramps",
            whatText = "Aching or throbbing contractions in the lower abdomen or pelvic basin right before and during menstruation.",
            understandText = "The uterine lining produces prostaglandins, chemical messengers that induce uterine muscle contractions to shed the endometrium. Higher prostaglandin levels equal more intense cramps.",
            careText = "Apply a warm heating pad or hot water bottle to the lower abdomen (heat is clinically proven as effective as mild analgesics). Sip warm ginger or chamomile tea, and practice gentle pelvic rocking.",
            watchText = "Keep track of how long cramps last. Typical primary dysmenorrhea peaks on days 1-2 and recedes significantly by day 3.",
            escalateText = "Seek medical advice if pain is so severe you vomit or miss work, radiates sharply down your legs, or starts worsening after age 25 (possible endometriosis or fibroids)."
        ),
        CareArticle(
            id = "urinary_burning_urgency",
            question = "What causes burning or urgency when urinating?",
            topic = "Urinary Health",
            whatText = "A stinging or burning sensation during urination, frequently accompanied by feeling an urgent need to go even when the bladder is empty.",
            understandText = "Often triggered when bacteria (commonly E. coli) enter the urethra and multiply in the bladder lining, causing acute mucosal inflammation (cystitis/UTI).",
            careText = "Drink copious amounts of plain water immediately to flush the bladder. Avoid caffeine, alcohol, and sugary drinks which irritate the bladder wall.",
            watchText = "Observe urine color, odor, and whether lower back or pelvic discomfort intensifies over the next 12 hours.",
            escalateText = "Consult a healthcare provider promptly. UTIs typically require a short course of targeted antibiotics. Seek immediate urgent care if you experience fever, chills, or flank pain."
        ),
        CareArticle(
            id = "headaches_before_period",
            question = "Why do I get headaches right before bleeding starts?",
            topic = "Hormonal Headaches",
            whatText = "Tension or throbbing headaches localized to the temples or forehead occurring 1 to 3 days before period onset.",
            understandText = "Directly triggered by the rapid withdrawal of circulating estrogen right before menstruation. Estrogen drops impact cranial blood vessel constriction and brain neurotransmitters.",
            careText = "Maintain steady hydration with electrolyte water. Eat regular meals to prevent blood sugar dips. Apply an ice pack to the neck and rest in a dim, quiet room.",
            watchText = "Note whether the headache responds to sleep, fluids, and gentle neck relaxation.",
            escalateText = "Consult a doctor if you experience neurological aura (visual flashes, numbness), sudden 'thunderclap' severity, or headaches unresponsive to treatment."
        ),
        CareArticle(
            id = "ovulation_spotting_pain",
            question = "Is mild spotting or twinges mid-cycle normal?",
            topic = "Ovulation & Mittelschmerz",
            whatText = "A brief light pink or brown speck of spotting, or a sharp one-sided twinge in the lower pelvic area roughly 2 weeks before your period.",
            understandText = "Known as Mittelschmerz ('middle pain'). As the follicle ruptures to release the mature egg, a tiny amount of fluid or follicular pressure causes mild local peritoneal irritation.",
            careText = "Relax with a warm cup of herbal tea. Mild twinges are a benign indicator that ovulation has successfully occurred.",
            watchText = "Confirm that the sensation resolves within 24 to 48 hours and doesn't progress into acute distress.",
            escalateText = "Seek urgent care if pain becomes severe, shifts to persistent rigidity, or is accompanied by high fever or dizziness."
        )
    )

    fun search(query: String): List<CareArticle> {
        val q = query.lowercase().trim()
        if (q.isBlank()) return articles
        return articles.filter {
            it.question.lowercase().contains(q) ||
            it.topic.lowercase().contains(q) ||
            it.whatText.lowercase().contains(q) ||
            it.careText.lowercase().contains(q)
        }
    }
}
