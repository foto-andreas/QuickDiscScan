#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#ifndef FILE_ATTRIBUTE_RECALL_ON_OPEN
#define FILE_ATTRIBUTE_RECALL_ON_OPEN 0x00040000
#endif
#ifndef FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS
#define FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS 0x00400000
#endif

static wchar_t *extended_path(const jchar *path, jsize length) {
    int unc = length >= 2 && path[0] == L'\\' && path[1] == L'\\';
    int already_extended = length >= 4 && path[0] == L'\\' && path[1] == L'\\'
            && path[2] == L'?' && path[3] == L'\\';
    const wchar_t *prefix = unc ? L"\\\\?\\UNC\\" : L"\\\\?\\";
    size_t prefix_length = already_extended ? 0 : (unc ? 8 : 4);
    size_t skip = unc && !already_extended ? 2 : 0;
    wchar_t *result = calloc(prefix_length + (size_t) length - skip + 1, sizeof(wchar_t));
    if (!result) return NULL;
    if (prefix_length) memcpy(result, prefix, prefix_length * sizeof(wchar_t));
    memcpy(result + prefix_length, path + skip, ((size_t) length - skip) * sizeof(wchar_t));
    return result;
}

JNIEXPORT jlongArray JNICALL Java_quickdiscscan_NativeDiskMetrics_read0(
        JNIEnv *env, jclass clazz, jstring java_path) {
    (void) clazz;
    const jchar *chars = (*env)->GetStringChars(env, java_path, NULL);
    if (!chars) return NULL;
    wchar_t *path = extended_path(chars, (*env)->GetStringLength(env, java_path));
    (*env)->ReleaseStringChars(env, java_path, chars);
    if (!path) return NULL;

    WIN32_FILE_ATTRIBUTE_DATA data;
    if (!GetFileAttributesExW(path, GetFileExInfoStandard, &data)) {
        free(path);
        return NULL;
    }

    uint64_t logical = ((uint64_t) data.nFileSizeHigh << 32) | data.nFileSizeLow;
    uint64_t physical = 0;
    uint64_t device = 0;
    uint64_t file_identity = 0;
    uint64_t link_count = 1;
    jlong flags = 0;
    int directory = (data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
    int reparse = (data.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
    if (directory) flags |= 2;
    else flags |= 8;
    /* Directory reparse points are junctions/symlinks and must not be traversed.
       Regular reparse points may be OneDrive or Google Drive placeholders. */
    if (directory && reparse) flags |= 4;
    if (data.dwFileAttributes & (FILE_ATTRIBUTE_OFFLINE
            | FILE_ATTRIBUTE_RECALL_ON_OPEN | FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS)) {
        flags |= 1;
    }

    if (!directory) {
        int sparse_or_compressed = (data.dwFileAttributes
                & (FILE_ATTRIBUTE_SPARSE_FILE | FILE_ATTRIBUTE_COMPRESSED)) != 0;
        HANDLE handle = CreateFileW(path, 0,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
        FILE_STANDARD_INFO standard_info;
        int has_standard_info = handle != INVALID_HANDLE_VALUE
                && GetFileInformationByHandleEx(handle, FileStandardInfo,
                        &standard_info, sizeof(standard_info));
        if (has_standard_info && standard_info.NumberOfLinks > 1) {
            FILE_ID_INFO id_info;
            if (GetFileInformationByHandleEx(handle, FileIdInfo, &id_info, sizeof(id_info))) {
                uint64_t low = 0;
                uint64_t high = 0;
                memcpy(&low, id_info.FileId.Identifier, sizeof(low));
                memcpy(&high, id_info.FileId.Identifier + sizeof(low), sizeof(high));
                device = id_info.VolumeSerialNumber;
                file_identity = low ^ high;
                link_count = standard_info.NumberOfLinks;
            }
        }
        if (sparse_or_compressed) {
            DWORD high = 0;
            SetLastError(NO_ERROR);
            DWORD low = GetCompressedFileSizeW(path, &high);
            DWORD error = GetLastError();
            if (low != INVALID_FILE_SIZE || error == NO_ERROR) {
                physical = ((uint64_t) high << 32) | low;
            }
        } else {
            /* DesiredAccess=0 asks for metadata only. It does not request file data
               and therefore does not hydrate a cloud placeholder. */
            if (has_standard_info) {
                physical = (uint64_t) standard_info.AllocationSize.QuadPart;
            }
            if (physical == 0 && logical > 0 && !(flags & 1)) {
                /* Some remote file systems do not expose FileStandardInfo. */
                physical = logical;
            }
        }
        if (handle != INVALID_HANDLE_VALUE) CloseHandle(handle);
        if (logical > 0 && physical == 0) flags |= 1;
    }
    free(path);

    jlong values[6] = {(jlong) logical, (jlong) physical, flags,
            (jlong) device, (jlong) file_identity, (jlong) link_count};
    jlongArray result = (*env)->NewLongArray(env, 6);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 6, values);
    return result;
}

#else
#include <sys/stat.h>

JNIEXPORT jlongArray JNICALL Java_quickdiscscan_NativeDiskMetrics_read0(
        JNIEnv *env, jclass clazz, jstring java_path) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, java_path, NULL);
    if (!path) return NULL;
    struct stat info;
    int status = lstat(path, &info);
    (*env)->ReleaseStringUTFChars(env, java_path, path);
    if (status != 0) return NULL;

    jlong flags = 0;
    if (S_ISDIR(info.st_mode)) flags |= 2;
    else if (S_ISLNK(info.st_mode)) flags |= 4;
    else if (S_ISREG(info.st_mode)) flags |= 8;

    jlong logical = S_ISREG(info.st_mode) ? (jlong) info.st_size : 0;
    jlong physical = S_ISREG(info.st_mode) ? (jlong) info.st_blocks * 512 : 0;
#ifdef __APPLE__
    if (info.st_flags & SF_DATALESS) flags |= 1;
#endif
    if (logical > 0 && physical == 0) flags |= 1;

    jlong values[6] = {logical, physical, flags, (jlong) info.st_dev,
            (jlong) info.st_ino, (jlong) info.st_nlink};
    jlongArray result = (*env)->NewLongArray(env, 6);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 6, values);
    return result;
}
#endif
