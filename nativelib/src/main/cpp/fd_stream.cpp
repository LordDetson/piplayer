#include "fd_stream.h"

#include <cerrno>
#include <limits>
#include <sys/stat.h>
#include <unistd.h>

namespace piplayer {

FdStream::FdStream(int fd, std::string name) : fd_(fd), name_(std::move(name)) {}

TagLib::FileName FdStream::name() const { return name_.c_str(); }

TagLib::ByteVector FdStream::readBlock(size_t length) {
  // Parsers pass sizes read from the file itself, so clamp to what is left, as TagLib's own FileStream does:
  // a damaged ID3v2 header can claim 256 MiB, an ASF attribute 4 GiB.
  const TagLib::offset_t remaining = this->length() - position_;
  if (length == 0 || remaining <= 0) return {};
  if (static_cast<unsigned long long>(length) > static_cast<unsigned long long>(remaining)) {
    length = static_cast<size_t>(remaining);
  }
  if (length > std::numeric_limits<unsigned int>::max()) length = std::numeric_limits<unsigned int>::max();

  TagLib::ByteVector buffer(static_cast<unsigned int>(length), 0);
  size_t done = 0;
  while (done < buffer.size()) {
    ssize_t count = pread(fd_, buffer.data() + done, buffer.size() - done, position_ + static_cast<off_t>(done));
    if (count < 0 && errno == EINTR) continue;
    if (count < 0) {
      error_ = errno;
      break;
    }
    if (count == 0) break;  // end of file
    done += static_cast<size_t>(count);
  }
  position_ += static_cast<TagLib::offset_t>(done);
  buffer.resize(static_cast<unsigned int>(done));
  return buffer;
}

void FdStream::writeBlock(const TagLib::ByteVector &) {}

void FdStream::insert(const TagLib::ByteVector &, TagLib::offset_t, size_t) {}

void FdStream::removeBlock(TagLib::offset_t, size_t) {}

bool FdStream::readOnly() const { return true; }

bool FdStream::isOpen() const { return fd_ >= 0; }

void FdStream::seek(TagLib::offset_t offset, Position position) {
  TagLib::offset_t base = 0;
  if (position == Current) base = position_;
  else if (position == End) base = length();
  position_ = base + offset < 0 ? 0 : base + offset;
}

void FdStream::clear() {}

TagLib::offset_t FdStream::tell() const { return position_; }

TagLib::offset_t FdStream::length() {
  struct stat info {};
  if (fstat(fd_, &info) != 0) {
    error_ = errno;
    return 0;
  }
  return static_cast<TagLib::offset_t>(info.st_size);
}

void FdStream::truncate(TagLib::offset_t) {}

int FdStream::error() const { return error_; }

}  // namespace piplayer
