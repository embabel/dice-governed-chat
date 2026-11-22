package example.dice.governed;

import com.embabel.dice.proposition.Proposition;

import java.util.regex.Pattern;

/** Classifies the fixture's refund statements before ordinary memory revision. */
final class RefundClaims {

    private static final String POLICY_TERMS = "(?:policy|window|deadline|eligibility|period|time limit|rules?)";
    private static final String REFUND_TOPIC =
            "(?:refunds?\\s+" + POLICY_TERMS + "|" + POLICY_TERMS + "\\s+(?:for|on)\\s+refunds?)";
    private static final String NUMBER = "(?:[0-9]+|one|two|three|four|five|six|seven|eight|nine|ten|twelve)";
    private static final String DURATION = NUMBER + "\\s+(?:hours?|days?|weeks?|months?|years?)";
    private static final String WEEKDAY = "(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)";
    private static final String POLICY_DATE = "(?:" + WEEKDAY + "|[0-9]{4}-[0-9]{2}-[0-9]{2})";
    private static final String REFUND_TIMING =
            "\\s+(?:(?:within|for(?:\\s+up\\s+to)?)\\s+" + DURATION
                    + "(?:\\s+after\\s+(?:purchase|delivery))?|(?:until|through)\\s+" + POLICY_DATE + ")";
    private static final String WORKSPACE_POLICY_END =
            "(?:\\s+for\\s+(?:all|every|any)\\s+(?:customers?|accounts?|orders?|purchases?))?"
                    + "(?:,?\\s+effective\\s+on\\s+" + POLICY_DATE + ")?[.!]?\\s*";
    private static final Pattern POLICY_TOPIC = Pattern.compile(
            "\\s*(?:(?:the|our|company|workspace)\\s+)?" + REFUND_TOPIC
                    + "(?:\\s*[:=]\\s*|\\s+(?:is|are|ends?|lasts?|expires?)\\s+)"
                    + "(?:" + DURATION + "|" + POLICY_DATE + "|(?:open|active)" + REFUND_TIMING + ")"
                    + WORKSPACE_POLICY_END,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern POLICY_REFERENCE = Pattern.compile("\\b" + REFUND_TOPIC + "\\b", Pattern.CASE_INSENSITIVE);
    private static final String APPROVAL_ROLES = "(?:support leads?|team leads?|supervisors?|managers?|administrators?)";
    private static final String MODIFIERS = "(?:(?:only|now|not)\\s+)*";
    private static final Pattern ROLE_AUTHORITY = Pattern.compile(
            "\\s*(?:only\\s+)?(?:the\\s+)?" + APPROVAL_ROLES + "\\s+(?:(?:can|cannot|may|must|should)\\s+" + MODIFIERS
                    + "|(?:is|are)\\s+" + MODIFIERS + "(?:authorized|allowed|required|permitted)\\s+to\\s+)"
                    + "(?:approve|authorize|issue|process)\\s+(?:an?\\s+)?refunds?"
                    + "(?:" + REFUND_TIMING + ")?" + WORKSPACE_POLICY_END,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERAL_REFUND_RULE = Pattern.compile(
            "\\s*(?:refunds\\s+(?:(?:can|cannot|may|must|should)\\s+" + MODIFIERS + "be|are)\\s+" + MODIFIERS
                    + "(?:approved|issued|processed|requested|available|allowed)(?:\\s+by\\s+" + APPROVAL_ROLES + ")?"
                    + "|customers\\s+are\\s+" + MODIFIERS + "eligible\\s+for\\s+refunds)"
                    + "(?:" + REFUND_TIMING + ")?" + WORKSPACE_POLICY_END,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUND = Pattern.compile("\\brefund(?:s|ed|ing)?\\b", Pattern.CASE_INSENSITIVE);
    private static final String HISTORICAL_ACTIONS = "(?:requested|received|issued|approved|processed|denied|rejected|canceled|cancelled)";
    private static final String CALENDAR_DATE =
            "(?:January|February|March|April|May|June|July|August|September|October|November|December)"
                    + "\\s+[0-9]{1,2}(?:,\\s*[0-9]{4})?";
    private static final String EVENT_DATE = "(?:\\s+(?:yesterday|today|" + DURATION + "\\s+ago"
            + "|on\\s+(?:" + POLICY_DATE + "|" + CALENDAR_DATE + ")))?[.!]?\\s*";
    private static final String SERVICE_ACTOR = "(?:(?:a|an|the)\\s+(?:customer|support agent|support lead)"
            + "|(?!(?:am|is|are|was|were|be|been|being)\\b)(?-i:[\\p{Lu}][\\p{L}'’\\-]*))";
    private static final Pattern HISTORICAL_REFUND_ACTION = Pattern.compile(
            "\\s*(?:" + SERVICE_ACTOR + "\\s+" + HISTORICAL_ACTIONS
                    + "\\s+(?:(?:a|an|the|their|his|her|" + NUMBER + ")\\s+)?(?:(?:full|partial)\\s+)?refunds?"
                    + "|(?:(?:a|an|the)\\s+)?(?:(?:full|partial)\\s+)?refunds?\\s+(?:was|were|has been|have been)\\s+"
                    + HISTORICAL_ACTIONS + ")" + EVENT_DATE,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUND_PERMISSION_CUES = Pattern.compile(
            "\\b(?:can|cannot|might|must|should|authorized|allowed|eligible|entitled|if|unless|whenever)\\b"
                    + "|\\b(?:every|each|anyone|everyone|always|never)\\b"
                    + "|\\bmay\\s+(?:only|now|be|have|approve|authorize|issue|process|request|receive)\\b",
            Pattern.CASE_INSENSITIVE);
    private RefundClaims() {}

    static boolean describesPolicy(Proposition fact) {
        var text = fact.getText();
        // Approval needs a complete workspace statement. A recognized prefix leaves its scope unknown.
        return POLICY_TOPIC.matcher(text).matches() || ROLE_AUTHORITY.matcher(text).matches()
                || GENERAL_REFUND_RULE.matcher(text).matches();
    }

    static boolean refundScopeIsUnclear(Proposition fact) {
        var text = fact.getText();
        boolean refund = REFUND.matcher(text).find() || fact.getMentions().stream()
                .anyMatch(mention -> mention.getType().equalsIgnoreCase("RefundPolicy"));
        boolean completed = HISTORICAL_REFUND_ACTION.matcher(text).matches()
                && !POLICY_REFERENCE.matcher(text).find()
                && !REFUND_PERMISSION_CUES.matcher(text).find();
        return refund && !completed;
    }
}
