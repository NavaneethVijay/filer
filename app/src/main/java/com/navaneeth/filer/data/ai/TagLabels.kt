package com.navaneeth.filer.data.ai

/**
 * Everyday tags the search model can recognise in a file, written the way people search ("bank
 * statement", "beach"). A file gets one when it stands out for that word just as it would in a
 * search. The user's own tags are added to these (see [TagSuggester]).
 */
object TagLabels {

    /** Kinds of document: also the first word of a suggested name ("2026-08-12 Invoice …"). */
    val documentTypes = listOf(
        "invoice", "receipt", "bill", "bank statement", "salary slip", "tax", "insurance", "loan",
        "contract", "agreement", "resume", "certificate", "id card", "passport", "aadhaar", "pan card",
        "driving licence", "ticket", "boarding pass", "hotel booking", "itinerary", "visa",
        "prescription", "medical report", "lab report", "vaccination", "marksheet", "exam",
        "notes", "letter", "form", "warranty", "manual", "recipe", "rent", "electricity bill",
        "presentation", "report", "brochure", "menu"
    )

    /** What's in a photo (or about in a recording). */
    val subjects = listOf(
        "selfie", "group photo", "family", "kids", "baby", "pet", "dog", "cat", "food", "drinks",
        "restaurant", "beach", "mountains", "nature", "sunset", "flowers", "city", "street", "car",
        "bike", "travel", "temple", "wedding", "party", "birthday", "festival", "concert", "sports",
        "gym", "shopping", "home", "office", "meeting", "whiteboard", "handwriting", "chat",
        "meme", "quote", "map", "qr code", "poster", "art", "night", "snow", "river", "lake",
        "boat", "airport", "train", "flight", "hiking", "garden", "building", "monument",
        "music", "lecture", "interview", "voice note", "game"
    )

    val all: List<String> = (documentTypes + subjects).distinct()

    /** The words a tag is searched by: "bank-statement" → "bank statement". */
    fun queryFor(tag: String): String = tag.replace('-', ' ').trim()
}
