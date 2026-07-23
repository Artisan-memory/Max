import os
import time
from pathlib import Path
from sys import argv

from pyrogram import Client
from pyrogram.enums import ParseMode
from pyrogram.types import InputMediaDocument, InputMediaPhoto

artifacts_path = Path("artifacts")
test_version = argv[3] == "test" if len(argv) > 2 else None
metadata_chat_id = argv[4] if len(argv) > 3 else None
bot_token = argv[1]
target_chat_id = argv[2]

# MTProto credentials. The Bot API caps bot uploads at 50 MB, which our APKs
# exceed, so we send over MTProto (Pyrogram) instead — that lifts the limit to
# 2 GB. api_id/api_hash are read from the environment first so they can be
# overridden by a CI secret; the defaults are the public TDesktop pair.
api_id = int(os.environ.get("APP_CONFIG_API_ID") or 2040)
api_hash = os.environ.get("APP_CONFIG_API_HASH") or "b18441a1ff607e10a989891a5462e627"


def _chat(value):
    # Pyrogram wants an int for numeric ids, str for @usernames.
    try:
        return int(value)
    except (TypeError, ValueError):
        return value


def find_apks(abi_list: list) -> list[Path]:
    found_files = []
    dirs = list(artifacts_path.glob("*"))
    for dir in dirs:
        if dir.is_dir():
            apks = list(dir.glob("*.apk"))
            for apk in apks:
                for abi in abi_list:
                    if abi in apk.name:
                        found_files.append(apk)
    return found_files


def get_commit_info():
    commit_id_raw = os.environ.get("COMMIT_ID") or "unknown"
    commit_id = commit_id_raw[:7]
    commit_url = os.environ.get("COMMIT_URL") or "https://github.com/risin42/NagramX/commits"
    commit_message = os.environ.get("COMMIT_MESSAGE") or "unknown"
    return commit_id, commit_url, commit_message


def get_caption() -> str:
    commit_id, commit_url, commit_message = get_commit_info()
    pre = "Test version." if test_version else "Release version."

    caption = f"{pre}\n\n"
    caption += f"Commit Message:\n<blockquote expandable>{commit_message}</blockquote>\n\n"
    caption += f"See commit details <a href='{commit_url}'>{commit_id}</a>"

    # Full commit list for this build as bullets, provided by the workflow.
    commit_list = os.environ.get("COMMIT_LIST", "").strip()
    if commit_list:
        block = f"\n\n<blockquote expandable>{commit_list}</blockquote>"
        if len(caption + block) <= 1024:
            caption += block

    compare_url = os.environ.get("COMPARE_URL", "").strip()
    if compare_url:
        link = f"\n\n<a href='{compare_url}'>Full changes</a>"
        if len(caption + link) <= 1024:
            caption += link

    if len(caption) > 1024:
        caption = caption[:1021] + "..."
    return caption


def get_metadata_text():
    commit_id = "<code>" + (os.environ.get("COMMIT_ID") or "unknown")[:7] + "</code>"
    commit_message = "<code>" + (os.environ.get("COMMIT_MESSAGE") or "unknown") + "</code>"
    build_timestamp = "<code>" + (os.environ.get("BUILD_TIMESTAMP") or "-1") + "</code>"
    return build_timestamp + " " + commit_id + "\n" + commit_message


def _with_retry(action, attempts=3):
    for i in range(attempts):
        try:
            return action()
        except Exception as e:
            print(f"Attempt {i + 1} failed: {e}")
            time.sleep(2)
    return None


def main():
    files_to_send = find_apks(["arm64-v8a"])

    if not files_to_send:
        fallback_img = Path("TMessagesProj/src/main/ic_launcher_max-playstore.png")
        if fallback_img.exists():
            files_to_send = [fallback_img]

    if not files_to_send:
        print("No files to upload.")
        return

    caption = get_caption()
    chat = _chat(target_chat_id)

    # in_memory keeps no session file on the CI runner.
    app = Client(
        "uploader",
        api_id=api_id,
        api_hash=api_hash,
        bot_token=bot_token,
        in_memory=True,
        parse_mode=ParseMode.HTML,
    )

    with app:
        print(f"Sending {len(files_to_send)} file(s) to {target_chat_id} over MTProto...")
        if len(files_to_send) == 1:
            file_path = files_to_send[0]
            if file_path.suffix == ".png":
                _with_retry(lambda: app.send_photo(chat, str(file_path), caption=caption))
            else:
                _with_retry(lambda: app.send_document(chat, str(file_path), caption=caption, force_document=True))
        else:
            media_group = []
            for idx, file_path in enumerate(files_to_send):
                item_caption = caption if idx == len(files_to_send) - 1 else None
                if file_path.suffix == ".png":
                    media_group.append(InputMediaPhoto(str(file_path), caption=item_caption))
                else:
                    media_group.append(InputMediaDocument(str(file_path), caption=item_caption))
            _with_retry(lambda: app.send_media_group(chat, media_group))

        if metadata_chat_id:
            print(f"Sending metadata to {metadata_chat_id}...")
            _with_retry(lambda: app.send_message(_chat(metadata_chat_id), get_metadata_text()))


if __name__ == "__main__":
    main()
