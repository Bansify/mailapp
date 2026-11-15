package com.gothwad.mail.data.local

import com.gothwad.mail.data.model.AppSettingsEntity
import com.gothwad.mail.data.model.AttachmentItem
import com.gothwad.mail.data.model.EmailThreadEntity
import com.gothwad.mail.data.model.MailAccountEntity
import com.gothwad.mail.data.model.MailFolder
import com.gothwad.mail.data.model.MeetingSession
import com.gothwad.mail.data.model.ThreadMessage

object SeedData {

    const val PRIMARY_EMAIL = "pawangothwad@gmail.com"
    const val WORK_EMAIL = "pawan@gothwadtech.dev"

    fun initialAccounts(): List<MailAccountEntity> = listOf(
        MailAccountEntity(
            email = PRIMARY_EMAIL,
            displayName = "Pawan Gothwad",
            avatarColorHex = 0xFF0B57D0,
            usedStorageGb = 4.6f,
            totalStorageGb = 15.0f,
            signature = "Best regards,\nPawan Gothwad",
            isActive = true
        ),
        MailAccountEntity(
            email = WORK_EMAIL,
            displayName = "Pawan · GothwadTech",
            avatarColorHex = 0xFF146C2E,
            usedStorageGb = 11.4f,
            totalStorageGb = 30.0f,
            signature = "Pawan Gothwad\nEngineering · GothwadTech",
            isActive = false
        )
    )

    fun initialSettings(): AppSettingsEntity = AppSettingsEntity(id = 1)

    fun initialMeetings(): List<MeetingSession> = listOf(
        MeetingSession(
            id = "meet-1",
            title = "Android Compose UI Review",
            code = "gtw-comp-482",
            timeLabel = "Today, 4:30 PM – 5:15 PM",
            hostName = "Ananya Verma",
            participantCount = 5
        ),
        MeetingSession(
            id = "meet-2",
            title = "Sprint 14 Architecture Sync",
            code = "syn-arch-910",
            timeLabel = "Tomorrow, 10:00 AM – 10:45 AM",
            hostName = "Pawan Gothwad",
            participantCount = 8
        )
    )

    fun initialThreads(now: Long = System.currentTimeMillis()): List<EmailThreadEntity> {
        val min = 60_000L
        val hour = 60 * min
        val day = 24 * hour

        return listOf(
            // 1. Multi-message unread thread in Primary with attachments
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Design review: MailApp Q4 release & Material 3 polish",
                snippet = "Awesome! I pushed the updated adaptive drawer tokens and attached the final spec PDF.",
                primarySenderName = "Ananya Verma",
                primarySenderEmail = "ananya.verma@gothwadtech.dev",
                primarySenderColorHex = 0xFF7B1FA2,
                folder = MailFolder.PRIMARY,
                isRead = false,
                isStarred = true,
                isImportant = true,
                labels = listOf("Work", "Design"),
                updatedAtMillis = now - 14 * min,
                messages = listOf(
                    ThreadMessage(
                        id = "m1-1",
                        senderName = "Ananya Verma",
                        senderEmail = "ananya.verma@gothwadtech.dev",
                        senderColorHex = 0xFF7B1FA2,
                        recipientsTo = listOf(PRIMARY_EMAIL, "rohan@gothwadtech.dev"),
                        body = "Hey Pawan,\n\nI finished reviewing the Material 3 compose tokens for the inbox list and thread reader. The pill search bar height (52dp) and surface container contrast look super crisp on both light and dark modes.\n\nCan you verify the swipe-to-archive threshold before we tag v1.4?",
                        timestampMillis = now - 3 * hour,
                        attachments = listOf(
                            AttachmentItem("att-1", "MailApp_M3_Spec_v4.pdf", "2.4 MB", "application/pdf"),
                            AttachmentItem("att-2", "Inbox_DarkMode_Preview.png", "840 KB", "image/png")
                        )
                    ),
                    ThreadMessage(
                        id = "m1-2",
                        senderName = "Pawan Gothwad",
                        senderEmail = PRIMARY_EMAIL,
                        senderColorHex = 0xFF0B57D0,
                        recipientsTo = listOf("ananya.verma@gothwadtech.dev"),
                        recipientsCc = listOf("rohan@gothwadtech.dev"),
                        body = "Thanks Ananya! Just tested it on a Pixel tablet and compact phone layout. Swipe threshold feels spot-on and the undo snackbar triggers smoothly.\n\nLet's finalize the release notes after lunch.",
                        timestampMillis = now - 95 * min
                    ),
                    ThreadMessage(
                        id = "m1-3",
                        senderName = "Ananya Verma",
                        senderEmail = "ananya.verma@gothwadtech.dev",
                        senderColorHex = 0xFF7B1FA2,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        recipientsCc = listOf("rohan@gothwadtech.dev"),
                        body = "Awesome! I pushed the updated adaptive drawer tokens and attached the final spec PDF.\n\nLet me know once the Android build passes QA so we can ship to the release track tonight.",
                        timestampMillis = now - 14 * min,
                        isStarred = true,
                        attachments = listOf(
                            AttachmentItem("att-3", "Release_Checklist_Q4.csv", "48 KB", "text/csv")
                        )
                    )
                )
            ),

            // 2. Unread Primary - Flight / Travel confirmation
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Your flight to Bengaluru (BLR) is confirmed · PNR K8M4QX",
                snippet = "Check-in opens 48 hours before departure. Terminal 3, Gate 14B · Boarding at 06:45 AM.",
                primarySenderName = "AirVista Airlines",
                primarySenderEmail = "reservations@airvista.in",
                primarySenderColorHex = 0xFF00838F,
                folder = MailFolder.PRIMARY,
                isRead = false,
                isStarred = true,
                isImportant = true,
                labels = listOf("Travel"),
                updatedAtMillis = now - 1 * hour,
                messages = listOf(
                    ThreadMessage(
                        id = "m2-1",
                        senderName = "AirVista Airlines",
                        senderEmail = "reservations@airvista.in",
                        senderColorHex = 0xFF00838F,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "Hello Pawan Gothwad,\n\nYour itinerary is confirmed for Flight AV-412.\n\n• Route: New Delhi (DEL) -> Bengaluru (BLR)\n• Departure: Nov 18, 2026 at 07:30 AM (Terminal 3)\n• Arrival: Nov 18, 2026 at 10:15 AM (Terminal 2)\n• Seat: 12A (Window · Extra Legroom)\n• Booking Reference: K8M4QX\n\nPlease arrive at least 90 minutes prior to departure. Your e-ticket PDF is attached below.",
                        timestampMillis = now - 1 * hour,
                        isStarred = true,
                        attachments = listOf(
                            AttachmentItem("att-4", "Boarding_Pass_K8M4QX.pdf", "512 KB", "application/pdf")
                        )
                    )
                )
            ),

            // 3. Unread Primary - GitHub Notification
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "[GothwadTech/mailapp] Pull Request #42 merged into main",
                snippet = "rohansharma merged 4 commits into main from feat/threaded-reader (248 additions, 31 deletions).",
                primarySenderName = "GitHub",
                primarySenderEmail = "notifications@github.com",
                primarySenderColorHex = 0xFF24292E,
                folder = MailFolder.PRIMARY,
                isRead = false,
                isImportant = true,
                labels = listOf("Work"),
                updatedAtMillis = now - 3 * hour,
                messages = listOf(
                    ThreadMessage(
                        id = "m3-1",
                        senderName = "GitHub",
                        senderEmail = "notifications@github.com",
                        senderColorHex = 0xFF24292E,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "Merged #42 into main.\n\nRepository: GothwadTech/mailapp\nAuthor: @rohansharma\nChecks: All 6 CI workflows passed (compileDebugKotlin, testDebugUnitTest, ktlintCheck).\n\nView diff and release artifacts on GitHub.",
                        timestampMillis = now - 3 * hour
                    )
                )
            ),

            // 4. Read Primary - Cloud Billing Receipt
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Cloud Platform Invoice #INV-2026-1108 is ready",
                snippet = "Total amount due: $18.40 · Auto-pay scheduled for Nov 20, 2026. Download your itemized statement.",
                primarySenderName = "Cloud Billing",
                primarySenderEmail = "billing-noreply@cloudinfra.io",
                primarySenderColorHex = 0xFF1565C0,
                folder = MailFolder.PRIMARY,
                isRead = true,
                isStarred = false,
                labels = listOf("Receipts"),
                updatedAtMillis = now - 8 * hour,
                messages = listOf(
                    ThreadMessage(
                        id = "m4-1",
                        senderName = "Cloud Billing",
                        senderEmail = "billing-noreply@cloudinfra.io",
                        senderColorHex = 0xFF1565C0,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "Hi Pawan,\n\nYour monthly statement for November 2026 is now available.\n\n• Compute & Containers: $12.20\n• Object Storage & CDN: $4.10\n• Managed DNS: $2.10\n• Total: $18.40 USD\n\nNo action is required. Auto-pay will process on Nov 20.",
                        timestampMillis = now - 8 * hour,
                        attachments = listOf(
                            AttachmentItem("att-5", "Invoice_Nov_2026.pdf", "195 KB", "application/pdf")
                        )
                    )
                )
            ),

            // 5. Read Primary - Personal multi-message thread
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Weekend trek photos & Rishikesh homestay booking",
                snippet = "Shared the full album link! Also confirmed our riverside cottage for Saturday night.",
                primarySenderName = "Vikram Mehta",
                primarySenderEmail = "vikram.mehta@mailapp.dev",
                primarySenderColorHex = 0xFFD84315,
                folder = MailFolder.PRIMARY,
                isRead = true,
                isStarred = false,
                labels = listOf("Personal", "Travel"),
                updatedAtMillis = now - 1 * day,
                messages = listOf(
                    ThreadMessage(
                        id = "m5-1",
                        senderName = "Vikram Mehta",
                        senderEmail = "vikram.mehta@mailapp.dev",
                        senderColorHex = 0xFFD84315,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "Bhai, we locked in the riverside homestay near Tapovan for Saturday night! Are we starting at 5:30 AM from Delhi?",
                        timestampMillis = now - 28 * hour
                    ),
                    ThreadMessage(
                        id = "m5-2",
                        senderName = "Vikram Mehta",
                        senderEmail = "vikram.mehta@mailapp.dev",
                        senderColorHex = 0xFFD84315,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "Shared the full album link! Also confirmed our riverside cottage for Saturday night. Pack a light fleece, evenings are dipping to 11°C.",
                        timestampMillis = now - 1 * day,
                        attachments = listOf(
                            AttachmentItem("att-6", "Rishikesh_Trail_Map.png", "1.3 MB", "image/png")
                        )
                    )
                )
            ),

            // 6. Promotions (Unread)
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Early Black Friday: Up to 45% off mechanical keyboards & studio gear",
                snippet = "Exclusive subscriber preview — aluminum 75% wireless boards, keycap sets, and coiled cables.",
                primarySenderName = "KeyCraft Studio",
                primarySenderEmail = "offers@keycraft.store",
                primarySenderColorHex = 0xFF2E7D32,
                folder = MailFolder.PROMOTIONS,
                previousFolder = MailFolder.PROMOTIONS,
                isRead = false,
                updatedAtMillis = now - 2 * hour,
                messages = listOf(
                    ThreadMessage(
                        id = "m6-1",
                        senderName = "KeyCraft Studio",
                        senderEmail = "offers@keycraft.store",
                        senderColorHex = 0xFF2E7D32,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "Unlock early access with code CRAFT45 at checkout.\n\nFeatured picks:\n• KeyCraft K75 Pro Aluminum Wireless — $119\n• PBT Double-shot Retro Keycaps — $39\n• Acoustic Desk Mat — $24\n\nOffer valid through Sunday midnight.",
                        timestampMillis = now - 2 * hour
                    )
                )
            ),

            // 7. Promotions (Unread)
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "JetBrains Developer Digest · Kotlin 2.2 & Compose Multiplatform highlights",
                snippet = "Explore faster incremental KSP builds, Kotlin compiler insights, and community showcase apps.",
                primarySenderName = "JetBrains Digest",
                primarySenderEmail = "news@jetbrains.com",
                primarySenderColorHex = 0xFFAD1457,
                folder = MailFolder.PROMOTIONS,
                previousFolder = MailFolder.PROMOTIONS,
                isRead = false,
                updatedAtMillis = now - 5 * hour,
                messages = listOf(
                    ThreadMessage(
                        id = "m7-1",
                        senderName = "JetBrains Digest",
                        senderEmail = "news@jetbrains.com",
                        senderColorHex = 0xFFAD1457,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "This week in Kotlin:\n\n1. Compose compiler metrics & stability tips for smoother 120Hz scrolling\n2. KSP2 incremental processing benchmarks\n3. New Android Studio profiling workflows",
                        timestampMillis = now - 5 * hour
                    )
                )
            ),

            // 8. Social (Unread)
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "3 developers starred your repository GothwadTech/mailapp",
                snippet = "dev-arjun, priya-codes, and samir99 starred GothwadTech/mailapp on GitHub.",
                primarySenderName = "GitHub Community",
                primarySenderEmail = "social@github.com",
                primarySenderColorHex = 0xFF1A73E8,
                folder = MailFolder.SOCIAL,
                previousFolder = MailFolder.SOCIAL,
                isRead = false,
                updatedAtMillis = now - 4 * hour,
                messages = listOf(
                    ThreadMessage(
                        id = "m8-1",
                        senderName = "GitHub Community",
                        senderEmail = "social@github.com",
                        senderColorHex = 0xFF1A73E8,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "Your open-source activity is growing!\n\ndev-arjun, priya-codes, and samir99 starred GothwadTech/mailapp in the last 24 hours.",
                        timestampMillis = now - 4 * hour
                    )
                )
            ),

            // 9. Updates (Unread)
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Security alert: New sign-in on Linux workstation",
                snippet = "Your account pawangothwad@gmail.com was signed in from a verified device in Bengaluru, IN.",
                primarySenderName = "Account Security",
                primarySenderEmail = "security@mailapp.dev",
                primarySenderColorHex = 0xFFE37400,
                folder = MailFolder.UPDATES,
                previousFolder = MailFolder.UPDATES,
                isRead = false,
                isImportant = true,
                updatedAtMillis = now - 6 * hour,
                messages = listOf(
                    ThreadMessage(
                        id = "m9-1",
                        senderName = "Account Security",
                        senderEmail = "security@mailapp.dev",
                        senderColorHex = 0xFFE37400,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "New sign-in detected.\n\nAccount: pawangothwad@gmail.com\nDevice: Linux x86_64 · Chrome\nTime: Nov 14, 2026 · 3:35 PM\n\nIf this was you, you don't need to do anything.",
                        timestampMillis = now - 6 * hour
                    )
                )
            ),

            // 10. Draft
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Product roadmap notes for Q1 Android sprint",
                snippet = "Draft: Hi team, putting together our top priorities for offline Room caching and...",
                primarySenderName = "Draft",
                primarySenderEmail = PRIMARY_EMAIL,
                primarySenderColorHex = 0xFFD93025,
                folder = MailFolder.DRAFT,
                previousFolder = MailFolder.DRAFT,
                isRead = true,
                updatedAtMillis = now - 42 * min,
                messages = listOf(
                    ThreadMessage(
                        id = "m10-1",
                        senderName = "Pawan Gothwad",
                        senderEmail = PRIMARY_EMAIL,
                        senderColorHex = 0xFF0B57D0,
                        recipientsTo = listOf("team@gothwadtech.dev"),
                        body = "Hi team,\n\nPutting together our top priorities for offline Room caching, adaptive tablet panes, and smooth attachment previews.",
                        timestampMillis = now - 42 * min
                    )
                )
            ),

            // 11. Sent
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Re: API contract for notifications webhook",
                snippet = "Sounds great! Updated the payload schema and deployed to staging.",
                primarySenderName = "To: Siddharth Rao",
                primarySenderEmail = "siddharth@gothwadtech.dev",
                primarySenderColorHex = 0xFF00796B,
                folder = MailFolder.SENT,
                previousFolder = MailFolder.SENT,
                isRead = true,
                updatedAtMillis = now - 2 * day,
                messages = listOf(
                    ThreadMessage(
                        id = "m11-1",
                        senderName = "Pawan Gothwad",
                        senderEmail = PRIMARY_EMAIL,
                        senderColorHex = 0xFF0B57D0,
                        recipientsTo = listOf("siddharth@gothwadtech.dev"),
                        body = "Sounds great! Updated the payload schema and deployed to staging. Let me know if the signature verification header works on your end.",
                        timestampMillis = now - 2 * day
                    )
                )
            ),

            // 12. Snoozed
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Annual domain renewal reminder · gothwadtech.dev",
                snippet = "Snoozed until Tomorrow, 9:00 AM · Auto-renewal enabled for 1 year.",
                primarySenderName = "NameRegistry",
                primarySenderEmail = "renewals@nameregistry.com",
                primarySenderColorHex = 0xFF5D4037,
                folder = MailFolder.SNOOZED,
                previousFolder = MailFolder.PRIMARY,
                isRead = true,
                snoozedUntilLabel = "Tomorrow, 9:00 AM",
                updatedAtMillis = now - 3 * day,
                messages = listOf(
                    ThreadMessage(
                        id = "m12-1",
                        senderName = "NameRegistry",
                        senderEmail = "renewals@nameregistry.com",
                        senderColorHex = 0xFF5D4037,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "Your domain gothwadtech.dev is scheduled to renew in 14 days. Verify your billing card or DNSSEC configuration before renewal.",
                        timestampMillis = now - 3 * day
                    )
                )
            ),

            // 13. Scheduled
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Monday Standup Agenda & Demo Links",
                snippet = "Scheduled for Monday, 8:00 AM · Hi everyone, here is the agenda for Monday's sync...",
                primarySenderName = "To: Engineering",
                primarySenderEmail = "engineering@gothwadtech.dev",
                primarySenderColorHex = 0xFF0B57D0,
                folder = MailFolder.SCHEDULED,
                previousFolder = MailFolder.SENT,
                isRead = true,
                scheduledForMillis = now + 2 * day,
                updatedAtMillis = now - 5 * hour,
                messages = listOf(
                    ThreadMessage(
                        id = "m13-1",
                        senderName = "Pawan Gothwad",
                        senderEmail = PRIMARY_EMAIL,
                        senderColorHex = 0xFF0B57D0,
                        recipientsTo = listOf("engineering@gothwadtech.dev"),
                        body = "Hi everyone,\n\nHere is the agenda for Monday's sync:\n1. MailApp v1.4 QA wrap-up\n2. Search index latency metrics\n3. Open Q&A",
                        timestampMillis = now - 5 * hour
                    )
                )
            ),

            // 14. Spam
            EmailThreadEntity(
                accountEmail = PRIMARY_EMAIL,
                subject = "Claim your unclaimed prize voucher today!!!",
                snippet = "Urgent notice: Click within 24 hours to verify your reward eligibility...",
                primarySenderName = "Promo Rewards",
                primarySenderEmail = "noreply@unknown-deals-99.biz",
                primarySenderColorHex = 0xFFC62828,
                folder = MailFolder.SPAM,
                previousFolder = MailFolder.SPAM,
                isRead = false,
                updatedAtMillis = now - 4 * day,
                messages = listOf(
                    ThreadMessage(
                        id = "m14-1",
                        senderName = "Promo Rewards",
                        senderEmail = "noreply@unknown-deals-99.biz",
                        senderColorHex = 0xFFC62828,
                        recipientsTo = listOf(PRIMARY_EMAIL),
                        body = "This message was flagged as Spam by MailApp protection filters.",
                        timestampMillis = now - 4 * day,
                        isEncryptedTls = false
                    )
                )
            ),

            // 15. Secondary Work Account email!
            EmailThreadEntity(
                accountEmail = WORK_EMAIL,
                subject = "Client Proposal: GothwadTech Mobile Modernization",
                snippet = "Attached the revised SOW and timeline milestones for review before Friday's call.",
                primarySenderName = "Meera Nair",
                primarySenderEmail = "meera@northstar-enterprise.com",
                primarySenderColorHex = 0xFF3949AB,
                folder = MailFolder.PRIMARY,
                isRead = false,
                isStarred = true,
                isImportant = true,
                labels = listOf("Work"),
                updatedAtMillis = now - 35 * min,
                messages = listOf(
                    ThreadMessage(
                        id = "m15-1",
                        senderName = "Meera Nair",
                        senderEmail = "meera@northstar-enterprise.com",
                        senderColorHex = 0xFF3949AB,
                        recipientsTo = listOf(WORK_EMAIL),
                        body = "Hi Pawan,\n\nThank you for the thorough architecture walkthrough. We've attached the revised SOW and timeline milestones for review ahead of Friday's executive sign-off.\n\nWarm regards,\nMeera Nair",
                        timestampMillis = now - 35 * min,
                        attachments = listOf(
                            AttachmentItem("att-7", "NorthStar_SOW_2026.pdf", "1.8 MB", "application/pdf")
                        )
                    )
                )
            ),
            EmailThreadEntity(
                accountEmail = WORK_EMAIL,
                subject = "Build Pipeline #884 succeeded · release-candidate",
                snippet = "Signed APK & AAB artifacts uploaded successfully to internal testing track.",
                primarySenderName = "CI Runner",
                primarySenderEmail = "ci@gothwadtech.dev",
                primarySenderColorHex = 0xFF146C2E,
                folder = MailFolder.PRIMARY,
                isRead = true,
                labels = listOf("Work"),
                updatedAtMillis = now - 6 * hour,
                messages = listOf(
                    ThreadMessage(
                        id = "m16-1",
                        senderName = "CI Runner",
                        senderEmail = "ci@gothwadtech.dev",
                        senderColorHex = 0xFF146C2E,
                        recipientsTo = listOf(WORK_EMAIL),
                        body = "Pipeline #884 passed all 42 unit tests and generated release artifacts in 2m 14s.",
                        timestampMillis = now - 6 * hour
                    )
                )
            )
        )
    }
}
