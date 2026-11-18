import os
import requests

# Aptoide Developer API Endpoint
APTOIDE_UPLOAD_URL = "https://ws75.aptoide.com/api/7/dev/uploadApp"

APK_PATH = r"C:\My Files\App-Projects\GhostTouchGuardian\store_assets\GhostTouchGuardian_v1.0.apk"
ICON_PATH = r"C:\My Files\App-Projects\GhostTouchGuardian\store_assets\icon_512.png"

def upload_to_aptoide(email, password_or_token, repo_name):
    """
    Directly uploads the signed APK from Antigravity to Aptoide.
    """
    if not os.path.exists(APK_PATH):
        print(f"Error: APK not found at {APK_PATH}")
        return False

    print("==================================================")
    print("   ANTIGRAVITY -> APTOIDE DIRECT PUBLISHER       ")
    print("==================================================")
    print(f"Target Store : {repo_name}")
    print(f"Uploading APK: {APK_PATH} ({os.path.getsize(APK_PATH) / 1024 / 1024:.2f} MB)")

    payload = {
        "email": email,
        "password": password_or_token,
        "repo": repo_name,
        "mode": "json"
    }

    files = {
        "apk": open(APK_PATH, "rb")
    }

    try:
        response = requests.post(APTOIDE_UPLOAD_URL, data=payload, files=files, timeout=120)
        print("Response Code:", response.status_code)
        print("Response Body:", response.text)

        if response.status_code == 200:
            print("\nSUCCESS! APK uploaded to Aptoide successfully.")
            return True
        else:
            print("\nUpload Failed. Check credentials or repo name.")
            return False
    except Exception as e:
        print("Error during upload:", e)
        return False

if __name__ == "__main__":
    import getpass
    print("Connect Antigravity to your Aptoide Developer Account:")
    email = input("Aptoide Email: ").strip()
    pwd = getpass.getpass("Aptoide Password or API Token: ").strip()
    repo = input("Aptoide Store Name (Repo): ").strip()

    upload_to_aptoide(email, pwd, repo)
