package com.isene.mail.data

import uniffi.fe2o3_mobile_core.Message

/**
 * One scope, read against one message. See [Settings.scope] for the
 * strings.
 *
 * Here rather than in the ViewModel because the background fetch counts
 * the widget's unread through the same scope, and had its own copy of
 * this rule that was already a version behind — Discord scopes counted
 * everything.
 */
object Scope {

    /**
     * What to call it on screen. One rule, because the chip in the app
     * and the widget's header have to say the same thing — a widget
     * showing one channel while claiming to be everything is worse than
     * one that says nothing.
     */
    fun label(scope: String, settings: Settings): String = when {
        scope.isEmpty() -> "All"
        scope.startsWith("view:") -> scope.removePrefix("view:")
        scope == "mail" -> "Mail"
        scope == "rss" -> "Feeds"
        scope == "discord" -> "Discord"
        scope == "discord:dm" -> "DMs"
        scope.startsWith("mail:") -> scope.removePrefix("mail:").substringBefore('@')
        scope.startsWith("discord:") -> settings.channels()
            .firstOrNull { it.id == scope.removePrefix("discord:") }?.name ?: "Channel"
        scope.startsWith("rss:") -> settings.feeds()
            .firstOrNull { it.url == scope.removePrefix("rss:") }?.title ?: "Feed"
        // A chat platform whose last message has been removed: still the
        // scope, just no longer in any list built from what is held.
        else -> scope.replaceFirstChar { it.uppercase() }
    }

    fun matches(m: Message, scope: String, settings: Settings): Boolean = when {
        scope.isEmpty() -> true
        // A view is any of its scopes, narrowed by its match. Defined in
        // terms of the same strings, so there is one rule, not two.
        scope.startsWith("view:") -> {
            val v = settings.views().firstOrNull { it.name == scope.removePrefix("view:") }
            when {
                v == null -> true
                v.scopes.isNotEmpty() && v.scopes.none { matches(m, it, settings) } -> false
                v.match.isEmpty() -> true
                else -> listOf(m.from, m.to, m.subject, m.account)
                    .any { it.contains(v.match, ignoreCase = true) }
            }
        }
        scope == "mail" || scope == "rss" || scope == "discord" -> m.source == scope
        scope.startsWith("mail:") -> m.source == "mail" && m.account == scope.removePrefix("mail:")
        scope.startsWith("rss:") -> m.source == "rss" && m.folder == scope.removePrefix("rss:")
        // A DM raises a notification and belongs to no channel we poll,
        // so relay's capture of it had nowhere to be but "all of
        // Discord". Anything Discord from outside the channel list is
        // one — the API path always stamps a channel id.
        scope == "discord:dm" ->
            m.source == "discord" && settings.channels().none { it.id == m.folder }
        scope.startsWith("discord:") ->
            m.source == "discord" && m.folder == scope.removePrefix("discord:")
        // A chat platform is its own source ("whatsapp", "sms", …),
        // named by the relay rather than by this app, so there is no
        // list to match against. Matching the source is the rule;
        // letting an unrecognised scope through showed the whole store.
        else -> m.source == scope
    }
}
