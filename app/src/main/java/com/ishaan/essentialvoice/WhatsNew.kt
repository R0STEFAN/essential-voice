package com.ishaan.essentialvoice

/**
 * The short list of what changed, shown under Updates.
 *
 * There are two sources and they answer different questions.
 *
 * The **local** list below travels inside the APK, so the build can always say
 * what it itself brought, with no network and no waiting. It is the one people
 * see straight after installing — which is exactly the moment they want it.
 *
 * The **remote** list comes from `update.json` and describes the build that is
 * out, not the one installed. That is the only one that can carry pictures,
 * because a picture has to be uploaded somewhere after the APK is already built.
 *
 * Adding an entry for a release means two edits: [local] here, and `whatsNew`
 * in `publish/update.json`. Keep both to one line each — this is a changelog
 * someone reads standing up.
 */
object WhatsNew {

    /**
     * One thing that changed.
     *
     * [image] is an absolute https URL to a picture; anything the release page
     * can host works. It is optional, and an entry without one is a normal
     * line of text — the panel does not leave a gap where a picture would be.
     */
    data class Item(
        val title: String,
        val body: String,
        val image: String? = null,
    )

    /**
     * What *this* build brought. Text only, by construction: a drawable would
     * have to ship in the APK, and pictures of a feature are usually made after
     * the build that contains it.
     */
    val local: List<Item> = listOf(
        Item(
            "What's new, in the app",
            "Updates now shows the short list of what each build changed, with " +
                "pictures when a release has them.",
        ),
        Item(
            "A way to chip in",
            "There is a support section at the bottom. Nothing in the app is " +
                "behind it — it is a link, and it is the only one.",
        ),
    )
}
