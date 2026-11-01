package io.github.lorddetson.piplayer.tagging;

import io.github.lorddetson.piplayer.tagging.TaggerReply;

/** The tag reader in the `:tagger` process (docs/library.md §4.1). */
interface ITagger {

    /** The ID of the `:tagger` process, which the main process kills when a call takes too long. */
    int pid();

    /** Reads the tags of [file], which the service closes; [fileName] helps TagLib pick the format. */
    TaggerReply read(in ParcelFileDescriptor file, @nullable String fileName);
}
