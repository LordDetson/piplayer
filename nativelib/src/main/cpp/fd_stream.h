#pragma once

#include <string>

#include <tiostream.h>

namespace piplayer {

/**
 * A read-only TagLib stream over a file descriptor that the caller owns. It reads with pread() at its own position,
 * so it neither moves the caller's file offset nor closes the descriptor (on Android 11+ closing a descriptor owned
 * by a ParcelFileDescriptor aborts the process). Writing is not supported.
 */
class FdStream : public TagLib::IOStream {
 public:
  FdStream(int fd, std::string name);

  TagLib::FileName name() const override;
  TagLib::ByteVector readBlock(size_t length) override;
  void writeBlock(const TagLib::ByteVector &data) override;
  void insert(const TagLib::ByteVector &data, TagLib::offset_t start, size_t replace) override;
  void removeBlock(TagLib::offset_t start, size_t length) override;
  bool readOnly() const override;
  bool isOpen() const override;
  void seek(TagLib::offset_t offset, Position position) override;
  void clear() override;
  TagLib::offset_t tell() const override;
  TagLib::offset_t length() override;
  void truncate(TagLib::offset_t length) override;

  /** The errno of a failed read, or 0. TagLib sees a failed read as the end of the file, so the caller asks. */
  int error() const;

 private:
  int fd_;
  // TagLib calls strlen() on name(), so it must never be null.
  std::string name_;
  TagLib::offset_t position_ = 0;
  int error_ = 0;
};

}  // namespace piplayer
