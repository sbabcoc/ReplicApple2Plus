package com.nordstrom.emulator.transfer;

/**
 * A file's contents as READ returns them.
 *
 * @param bytes the file's bytes, exactly as the guest stores them (headers
 *              the guest OS keeps inside files already removed by the adapter)
 * @param type  the file's type as the adapter learned it while reading, or
 *              null if it sent none -- its listing entry's type then stands
 */
public record GuestData(byte[] bytes, FileType type) {
}
