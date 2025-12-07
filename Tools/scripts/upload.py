import os
import json
import time
from pathlib import Path
from sys import argv
import requests

artifacts_path = Path("artifacts")
test_version = argv[3] == "test" if len(argv) > 2 else None
metadata_chat_id = argv[4] if len(argv) > 3 else None
bot_token = argv[1]
target_chat_id = argv[2]

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
    
    ai_summary = os.environ.get("AI_SUMMARY", "")
    if ai_summary:
        summary_text = f"\n\n<blockquote expandable>{ai_summary.replace(r'\n', '\n')}</blockquote>"
        if len(caption + summary_text) <= 1024:
            caption += summary_text
            
    return caption

def get_metadata_text():
    commit_id = "<code>" + (os.environ.get("COMMIT_ID") or "unknown")[:7] + "</code>"
    commit_message = "<code>" + (os.environ.get("COMMIT_MESSAGE") or "unknown") + "</code>"
    build_timestamp = "<code>" + (os.environ.get("BUILD_TIMESTAMP") or "-1") + "</code>"
    return build_timestamp + " " + commit_id + "\n" + commit_message

def send_request_with_retry(method, data, files=None):
    url = f"https://api.telegram.org/bot{bot_token}/{method}"
    for i in range(3):
        try:
            response = requests.post(url, data=data, files=files)
            response.raise_for_status()
            return response.json()
        except Exception as e:
            print(f"Attempt {i+1} failed: {e}")
            if response is not None:
                print(response.text)
            time.sleep(2)
    return None

def main():
    files_to_send = find_apks(["arm64-v8a"])
    
    if not files_to_send:
        fallback_img = Path("TMessagesProj/src/main/ic_launcher_nagram_block_round-playstore.png")
        if fallback_img.exists():
            files_to_send = [fallback_img]
    
    if not files_to_send:
        print("No files to upload.")
        return


    media_group = []
    opened_files = {}
    
    caption = get_caption()
    
    try:
        for idx, file_path in enumerate(files_to_send):
            # Ключ для files dict и attach:// uri
            file_key = f"file{idx}"
            opened_files[file_key] = open(file_path, "rb")
            
            media_type = "photo" if file_path.suffix == ".png" else "document"
            
            media_item = {
                "type": media_type,
                "media": f"attach://{file_key}"
            }
            
            # Добавляем подпись только к последнему файлу в группе
            if idx == len(files_to_send) - 1:
                media_item["caption"] = caption
                media_item["parse_mode"] = "HTML"
                
            media_group.append(media_item)

        # Отправка файлов в канал
        payload = {
            "chat_id": target_chat_id,
            "media": json.dumps(media_group)
        }
        
        print(f"Sending {len(files_to_send)} files to {target_chat_id}...")
        send_request_with_retry("sendMediaGroup", payload, opened_files)
        
    finally:
        for f in opened_files.values():
            f.close()

    if metadata_chat_id:
        print(f"Sending metadata to {metadata_chat_id}...")
        meta_payload = {
            "chat_id": metadata_chat_id,
            "text": get_metadata_text(),
            "parse_mode": "HTML"
        }
        send_request_with_retry("sendMessage", meta_payload)

if __name__ == "__main__":
    main()