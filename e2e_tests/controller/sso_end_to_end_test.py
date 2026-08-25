import subprocess
import logging
import requests
import urllib3

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
logger = logging.getLogger(__name__)

def check_sso_docker_containers() -> bool:
    """
    Check if required Docker containers for API and Storage Service are running.
    """
    try:
        # Run docker ps to get running container names and status
        result = subprocess.run(
            ["docker", "ps", "--format", "{{.Names}} {{.Image}} {{.Status}}"],
            capture_output=True,
            text=True,
            check=True
        )
        output = result.stdout.lower()
        
        # Check storage-service containers
        has_storage_app = any("storage-service-mail-and-media-shop-v2-app" in line or ("storage-service-mail-and-media-shop-v2" in line and "app" in line) for line in output.splitlines())
        has_storage_postgres = "storage-service-mail-and-media-shop-v2-postgres" in output
        has_storage_minio = "storage-service-mail-and-media-shop-v2-minio" in output
        
        # Check API containers
        has_keycloak_db = "keycloak-db" in output
        has_keycloak = any(line.split()[0] == "keycloak" or ("keycloak" in line and "setup" not in line and "db" not in line) for line in output.splitlines() if line)
        has_shop_db = "shop-db" in output
        has_shop_redis = "shop-redis" in output
        has_api_app = "mail_and_media_shop_v2" in output or "api-mail-and-media-shop-v2" in output
        
        required_containers = {
            "storage_app": has_storage_app,
            "storage_postgres": has_storage_postgres,
            "storage_minio": has_storage_minio,
            "keycloak_db": has_keycloak_db,
            "keycloak": has_keycloak,
            "shop_db": has_shop_db,
            "shop_redis": has_shop_redis,
            "api_app": has_api_app
        }
        
        missing = [name for name, exists in required_containers.items() if not exists]
        if missing:
            logger.warning(f" 🛑 Missing required docker containers for SSO integration test: {missing}")
            return False
            
        return True
    except Exception as e:
        logger.warning(f"Could not check Docker containers status due to: {e}. Skipping SSO integration test.")
        return False

def test_sso_integration(
    kc_url: str,
    realm: str,
    client_id: str,
    client_secret: str,
    username: str,
    password: str,
    api_url: str,
    storage_url: str,
    token_helper_fn
):
    """
    E2E Test to verify SSO integration between API and Storage Service.
    It obtains a token using the public client and verifies it works on both services.
    """
    logger.info("================================================================================")
    logger.info("SSO E2E TEST: Verifying Single Sign-On between API and Storage Service")
    logger.info("================================================================================")

    # 1. Skip test if required Docker containers are not running
    if not check_sso_docker_containers():
        logger.warning(" ⚠️ SSO E2E TEST SKIPPED: Required Docker containers are not running or healthy.")
        return True

    try:
        # 2. Get token from Keycloak using the public client configuration
        logger.info(f"Obtaining access token from Keycloak for client '{client_id}'...")
        token = token_helper_fn(
            kc_url=kc_url,
            realm=realm,
            client_id=client_id,
            client_secret=client_secret,
            grant_type="password",
            username=username,
            password=password
        )
        
        assert token, "Failed to retrieve access token from Keycloak"
        logger.info("Access token successfully retrieved.")

        headers = {
            "Authorization": f"Bearer {token}"
        }

        logger.info(f"Testing access token against Main API at {api_url}...")

         # 3. Test token the Storage Service 
        logger.info(f"Testing access token against Storage Service at {storage_url}...")
        storage_response = requests.get(f"{storage_url.rstrip('/')}/api/v1/avatars/me", headers=headers, verify=False, timeout=10)

        # 4. Test token against with API mail and madia shop (SSO)
        api_response = requests.get(f"{api_url.rstrip('/')}/api/v1/shop/customers/me", headers=headers, verify=False, timeout=10)
        assert api_response.status_code in [200, 404], f"Main API auth failed with status code: {api_response.status_code}"
        logger.info("Main API successfully validated token (SSO verification PASSED).")

        # 404 is a valid authorized response (user authenticated, but avatar doesn't exist yet)
        # Any other error code like 401 or 403 means authentication failed
        assert storage_response.status_code in [200, 404], f"Storage Service auth failed with status code: {storage_response.status_code}"
        logger.info("Storage Service successfully validated token (SSO verification PASSED).")
        logger.info("================================================================================")
        logger.info("SSO E2E TEST PASSED SUCCESSFULLY")
        logger.info("================================================================================")
        return True

    except Exception as e:
        logger.error(f"SSO E2E TEST FAILED: {e}")
        raise e
