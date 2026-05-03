package com.acronet.forensics

import java.security.SecureRandom

/**
 * AcroNetDecoyGenerator — Believable Decoy Chat History Factory
 *
 * Generates forensically plausible fake conversations that would
 * pass inspection by a human examiner. Topics are drawn from
 * benign categories: Sports, Weather, Tech News, Academics.
 *
 * R.D.T.C. Compliance:
 * - REVIEW: Messages use realistic timestamps with jitter
 * - DEBUG: No real user data leaks into decoy generation
 * - TEST: Generated chats verified for topic coherence
 * - CORRECT: Timestamp spacing mimics real human typing cadence
 */
object AcroNetDecoyGenerator {

    private val rng = SecureRandom()

    data class DecoyMessage(
        val sender: String,
        val content: String,
        val timestampMs: Long,
        val isRead: Boolean
    )

    data class DecoyConversation(
        val contactName: String,
        val contactAvatar: Int, // Resource ID placeholder
        val messages: List<DecoyMessage>,
        val lastActivity: Long
    )

    // ── Benign conversation templates ───────────────────────────────

    private val SPORTS_CONVOS = listOf(
        listOf("Did you watch the match yesterday?", "Yeah it was insane! 3-2 in the last minute", "Kohli was on fire", "True legend. When's the next match?", "Thursday 7pm IST", "I'll be there. Hostel common room?", "Done. Bring chips 😂"),
        listOf("Bro have you seen the IPL points table?", "CSK is dominating as usual", "Dhoni effect even after retirement", "Lol true. RCB is a meme", "Every year same story", "At least they're consistent 💀"),
        listOf("Football practice tomorrow 6am", "Bhai itna early?", "Ground is booked after 8", "Fine I'll be there", "Bring the new ball", "Ok done")
    )

    private val WEATHER_CONVOS = listOf(
        listOf("It's so hot today 🥵", "42 degrees according to weather app", "AC is broken in the library", "Come to the canteen, they have coolers", "On my way", "Get me a cold coffee"),
        listOf("Is it going to rain today?", "Forecast says 60% chance after 5pm", "Should I carry an umbrella?", "Definitely. Got drenched yesterday", "Same happened to me last week", "Monsoon is coming early this year")
    )

    private val TECH_CONVOS = listOf(
        listOf("Have you tried the new ChatGPT update?", "Yeah the voice mode is crazy", "It sounds so natural now", "I used it for my assignment 😂", "Don't let the prof find out", "He probably uses it too lol"),
        listOf("Which laptop should I buy?", "What's your budget?", "Around 60-70k", "Go for ASUS Vivobook or HP Pavilion", "What about MacBook Air?", "M3 is amazing but no gaming", "I don't game much. Will check it out", "Let me send you the Amazon link"),
        listOf("DSA practice kar raha hai?", "Haan LeetCode daily", "Which topic today?", "Dynamic Programming 😭", "Same bro. It's so hard", "Try doing the easy ones first", "Good idea. Thanks!")
    )

    private val ACADEMIC_CONVOS = listOf(
        listOf("When is the assignment deadline?", "Wednesday 11:59 PM", "Bhai I haven't even started", "Same 💀", "Let's do it together tomorrow?", "Library at 2?", "Done. Bring your laptop", "And charger. Mine dies in 1 hour"),
        listOf("Did you attend today's lecture?", "No I overslept", "Prof took attendance", "Are you serious? 😰", "Yeah but I'll talk to the CR", "Thanks bhai. You're a lifesaver", "No problem. Notes bhi bhej dunga"),
        listOf("Mid semester ka syllabus kya hai?", "Unit 1 to 3", "That's so much content", "Start with Unit 2, easiest hai", "Ok. Any good YouTube channels?", "Neso Academy for CN", "Thanks! Will start tonight")
    )

    private val CONTACT_NAMES = listOf(
        "Rahul", "Priya", "Amit", "Sneha", "Vikram",
        "Neha", "Arjun", "Kavita", "Ravi", "Pooja",
        "Mohit", "Anjali", "Deepak", "Shivani", "Kunal",
        "Sakshi", "Aditya", "Megha", "Rohan", "Divya"
    )

    /**
     * Generate a full decoy chat history with N conversations.
     * Messages have realistic timestamps spanning the last 7 days.
     */
    fun generateDecoyHistory(conversationCount: Int = 8): List<DecoyConversation> {
        val allTemplates = SPORTS_CONVOS + WEATHER_CONVOS + TECH_CONVOS + ACADEMIC_CONVOS
        val usedNames = mutableSetOf<String>()
        val conversations = mutableListOf<DecoyConversation>()

        for (i in 0 until conversationCount.coerceAtMost(allTemplates.size)) {
            // Pick a unique contact name
            var name: String
            do { name = CONTACT_NAMES[rng.nextInt(CONTACT_NAMES.size)] } while (name in usedNames)
            usedNames.add(name)

            val template = allTemplates[i % allTemplates.size]
            val messages = generateTimedMessages(name, template)

            conversations.add(DecoyConversation(
                contactName = name,
                contactAvatar = 0,
                messages = messages,
                lastActivity = messages.lastOrNull()?.timestampMs ?: System.currentTimeMillis()
            ))
        }

        return conversations.sortedByDescending { it.lastActivity }
    }

    private fun generateTimedMessages(contactName: String, template: List<String>): List<DecoyMessage> {
        val messages = mutableListOf<DecoyMessage>()
        val now = System.currentTimeMillis()
        val daysAgo = rng.nextInt(5) + 1 // 1-5 days ago
        var timestamp = now - (daysAgo * 86_400_000L) + rng.nextInt(3_600_000) // Random hour offset

        for ((index, text) in template.withIndex()) {
            val isIncoming = index % 2 == 0
            val sender = if (isIncoming) contactName else "You"

            messages.add(DecoyMessage(
                sender = sender,
                content = text,
                timestampMs = timestamp,
                isRead = true
            ))

            // Realistic typing cadence: 15s-180s between messages
            timestamp += (15_000L + rng.nextInt(165_000))
        }

        return messages
    }

    /**
     * Generate a single plausible message for live decoy insertion.
     * Used when the decoy database needs to "grow" naturally over time.
     */
    fun generateLiveDecoyMessage(contactName: String): DecoyMessage {
        val liveTemplates = listOf(
            "Hey, what's up?",
            "Nothing much, studying for exams",
            "Same here 😅",
            "Want to grab lunch?",
            "Sure, canteen at 1?",
            "Did you finish the lab report?",
            "Almost done, just the conclusion left",
            "Can you send me the PPT?",
            "Sure, give me 5 mins",
            "Thanks! 🙌"
        )
        return DecoyMessage(
            sender = contactName,
            content = liveTemplates[rng.nextInt(liveTemplates.size)],
            timestampMs = System.currentTimeMillis(),
            isRead = false
        )
    }
}
