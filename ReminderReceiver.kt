package ir.behzad.accounting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("behzad_data", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notifications_enabled", true)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "behzad_due_reminders"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "یادآورهای حسابداری بهزاد", NotificationManager.IMPORTANCE_DEFAULT))
        }
        fun notifyReminder(id: Int, title: String, body: String) {
            val open = PendingIntent.getActivity(context, 9202 + id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, channelId).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
            }
            try { manager.notify(id, notification) } catch (_: SecurityException) { }
        }

        if (prefs.getBoolean("transaction_reminders", true)) {
            notifyReminder(9199, "یادآور تراکنش‌ها", "ثبت فروش، خرید، درآمد و هزینه‌های امروز را بررسی کنید.")
        }

        val debts = try { JSONArray(prefs.getString("debts", "[]")) } catch (_: Exception) { JSONArray() }
        val target = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 3) }
        val targetDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(target.time)
        val debtRemindersEnabled = prefs.getBoolean("receivable_payable_reminders", true)
        for (i in 0 until debts.length()) {
            if (!debtRemindersEnabled) continue
            val debt = debts.optJSONObject(i) ?: continue
            if (debt.optString("status") == "تسویه‌شده" || debt.optString("dueDate") != targetDate) continue
            val key = "reminded_${i}_${targetDate}"
            if (prefs.getBoolean(key, false)) continue
            val title = "سررسید نزدیک است: ${debt.optString("kind", "بدهی/طلب")}"
            val body = "${debt.optString("person")} — ${debt.optString("title")} — مبلغ ${debt.optLong("amount")}، سررسید $targetDate"
            val open = PendingIntent.getActivity(context, 9202, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, channelId).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
            }
            try { manager.notify((debt.optString("person") + targetDate + i).hashCode(), notification); prefs.edit().putBoolean(key, true).apply() } catch (_: SecurityException) { }
        }

        if (prefs.getBoolean("check_reminders", true)) {
            val checks = try { JSONArray(prefs.getString("checks", "[]")) } catch (_: Exception) { JSONArray() }
            for (i in 0 until checks.length()) {
                val check = checks.optJSONObject(i) ?: continue
                if (check.optString("status") == "تسویه‌شده" || check.optString("dueDate") != targetDate) continue
                val key = "check_reminded_${check.optString("dueDate")}_${check.optString("direction")}_${check.optLong("amount")}_$i"
                if (prefs.getBoolean(key, false)) continue
                val title = "سررسید چک ${check.optString("direction")} نزدیک است"
                val body = "${check.optString("party")} — مبلغ ${check.optLong("amount")} — سررسید $targetDate"
                val open = PendingIntent.getActivity(context, 9300 + i, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, channelId).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build()
                }
                try { manager.notify(("check" + key).hashCode(), notification); prefs.edit().putBoolean(key, true).apply() } catch (_: SecurityException) { }
            }
        }

        if (prefs.getBoolean("installment_reminders", true)) {
            val installments = try { JSONArray(prefs.getString("installments", "[]")) } catch (_: Exception) { JSONArray() }
            for (i in 0 until installments.length()) {
                val item = installments.optJSONObject(i) ?: continue
                if (item.optString("status") == "تسویه‌شده" || item.optString("dueDate") != targetDate) continue
                val key = "installment_reminded_${item.optString("id", i.toString())}_$targetDate"
                if (prefs.getBoolean(key, false)) continue
                val title = "سررسید قسط نزدیک است: ${item.optString("direction", "قسط")}"
                val body = "${item.optString("party")} — ${item.optString("title")} — مبلغ ${item.optLong("amount")}، سررسید $targetDate"
                val id = ("installment" + key).hashCode()
                notifyReminder(id, title, body)
                prefs.edit().putBoolean(key, true).apply()
            }
        }

    }
}
