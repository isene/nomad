package com.isene.onepage

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.widget.Toast
import java.io.File
import uniffi.fe2o3_mobile_core.Target
import uniffi.fe2o3_mobile_core.panelFill

/**
 * Lets the user pick one phone number from the phone's own contact list,
 * and puts it in the app panel as an icon that calls it. Then the panel
 * comes back, still in editing.
 *
 * The list is the contact app's, so OnePage reads no contact but the one
 * that is picked: its name, its number and its picture. The picture is
 * copied into OnePage's own files, and the panel never asks the contact
 * list again.
 */
class ContactActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Made anew while the list or a question is up: its answer still comes.
        if (savedInstanceState != null) return
        val missing = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) pick() else requestPermissions(missing.toTypedArray(), ASK)
    }

    /** On with a yes or a no. With no leave to read contacts the icon is a
     *  letter; with no leave to call, a tap opens the dialler. */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) =
        pick()

    private fun pick() {
        try {
            startActivityForResult(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI), PICK)
        } catch (_: Exception) {
            say("No contact list answered")
            done()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val picked = data?.data
        if (requestCode == PICK && resultCode == RESULT_OK && picked != null) add(picked)
        done()
    }

    private fun add(picked: Uri) {
        try {
            val columns = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.PHOTO_URI)
            contentResolver.query(picked, columns, null, null, null)?.use { row ->
                if (!row.moveToFirst()) return
                val number = row.getString(1)?.trim().orEmpty()
                if (number.isEmpty()) return
                val name = row.getString(0)?.trim().orEmpty().ifEmpty { number }
                val target = Target.Call(name, number)
                keepFace(row.getString(2), number)
                Icons.forget(target)
                // The slot the plus was in, or the next free one after it.
                val at = intent.getIntExtra(AT, 0)
                PanelStore.set(this, panelFill(PanelStore.get(this), at.toUInt(), listOf(target)))
            }
        } catch (_: Exception) {
            say("That contact could not be read")
        }
    }

    /** Copy the contact's picture into OnePage's files, small and square.
     *  With no picture, or no leave to read it, an old copy goes. */
    private fun keepFace(photo: String?, number: String) {
        val file = File(filesDir, faceFile(number))
        val picture = try {
            photo?.let { uri -> contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) } }
        } catch (_: Exception) {
            null
        }
        if (picture == null) {
            file.delete()
            return
        }
        try {
            val side = minOf(picture.width, picture.height)
            val square = Bitmap.createBitmap(picture, (picture.width - side) / 2, (picture.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(square, FACE, FACE, true)
            file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
            file.delete()
        }
    }

    private fun say(what: String) = Toast.makeText(applicationContext, what, Toast.LENGTH_LONG).show()

    private fun done() {
        PanelWindow.show(this, edit = true)
        finish()
    }

    companion object {
        /** The slot the number goes to, handed over by the panel. */
        const val AT = "at"

        private const val ASK = 1
        private const val PICK = 2

        /** A kept picture's side, in pixels: enough for the largest icon. */
        private const val FACE = 192
    }
}
