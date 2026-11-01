package io.github.lorddetson.piplayer.tagging

import android.os.Parcel
import android.os.Parcelable

/** A [TagReplyBody] as `:tagger` returns it over binder (ITagger.aidl). */
class TaggerReply(val body: TagReplyBody) : Parcelable {

    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        when (body) {
            is TagReplyBody.Inline -> {
                dest.writeInt(INLINE)
                dest.writeByteArray(body.json)
            }
            is TagReplyBody.InFile -> {
                dest.writeInt(IN_FILE)
                dest.writeString(body.path)
            }
        }
    }

    companion object {
        private const val INLINE = 0
        private const val IN_FILE = 1

        @JvmField
        val CREATOR = object : Parcelable.Creator<TaggerReply> {
            override fun createFromParcel(source: Parcel): TaggerReply = when (val kind = source.readInt()) {
                INLINE -> TaggerReply(TagReplyBody.Inline(source.createByteArray()!!))
                IN_FILE -> TaggerReply(TagReplyBody.InFile(source.readString()!!))
                else -> throw IllegalArgumentException("Unknown reply kind $kind")
            }

            override fun newArray(size: Int): Array<TaggerReply?> = arrayOfNulls(size)
        }
    }
}
