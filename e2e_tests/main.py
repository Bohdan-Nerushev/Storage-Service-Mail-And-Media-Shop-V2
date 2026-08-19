#!/usr/bin/env python3
"""
End-to-End Tests for Storage Service - Avatar API

This test suite covers all avatar endpoints with both positive and negative scenarios.
Tests validate:
- HTTP status codes
- Response body structure and content
- Error handling (400, 401, 404, 409, 413, 415)
- Image format validation (JPEG, PNG, WebP)
- File size validation
- Idempotent operations (DELETE is idempotent)

Setup: Ensure docker-compose.yml is running and services are healthy
Run: python main.py
"""

import os
import sys
import copy
import uuid
import logging
import urllib3
from pathlib import Path
from dotenv import load_dotenv

import requests

# Import test functions
from controller.avatars_controller_end_to_end_api_test import (
    test_get_avatar_success_with_avatar,
    test_get_avatar_success_without_avatar,
    test_get_avatar_unauthorized,
    test_get_avatar_content_success,
    test_get_avatar_content_not_found,
    test_get_avatar_content_unauthorized,
    test_upload_avatar_success_jpeg,
    test_upload_avatar_success_png,
    test_upload_avatar_success_webp,
    test_upload_avatar_empty_file,
    test_upload_avatar_unreadable_file,
    test_upload_avatar_exceeds_size_limit,
    test_upload_avatar_unsupported_media_type,
    test_upload_avatar_truncated_signature,
    test_upload_avatar_conflict_concurrent_modification,
    test_upload_avatar_unauthorized,
    test_delete_avatar_success,
    test_delete_avatar_not_found_idempotent,
    test_delete_avatar_unauthorized,
)

from test_helpers import (
    generate_valid_jpeg,
    generate_valid_png,
    generate_valid_webp,
    generate_empty_file,
    generate_invalid_file,
    generate_oversized_file,
    generate_truncated_png,
    generate_corrupted_jpeg,
    get_user_token,
    delete_keycloak_user,
)
from controller.sso_end_to_end_test import test_sso_integration

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# ============================================================================
# Configure Logging
# ============================================================================

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s'
)
logger = logging.getLogger(__name__)

# ============================================================================
# Configuration from Environment
# ============================================================================

# Load environment variables from .env file in project root
env_path = Path(__file__).resolve().parent.parent / '.env'
if env_path.exists():
    load_dotenv(dotenv_path=env_path)
    logger.info(f"Loaded .env from {env_path}")
else:
    logger.warning(f".env file not found at {env_path}, using system environment variables")

# Storage service configuration
STORAGE_APP_HOST = os.getenv("STORAGE_APP_HOST", "http://localhost").rstrip("/")
STORAGE_APP_PORT = os.getenv("STORAGE_APP_PORT", "8080")
STORAGE_APP_URL = f"{STORAGE_APP_HOST}:{STORAGE_APP_PORT}"

# Main API configuration
API_APP_HOST = os.getenv("API_APP_HOST", "http://localhost").rstrip("/")
API_APP_PORT = os.getenv("API_APP_PORT", "8090")
API_APP_URL = f"{API_APP_HOST}:{API_APP_PORT}"

# Keycloak configuration
KC_URL = os.getenv("KC_URL", "https://localhost:8443")
KC_REALM = os.getenv("KC_REALM", "mail-and-media-shop-realm")
KC_CLIENT_ID = os.getenv("KC_CLIENT_ID", "storage-service-app")
KC_CLIENT_SECRET = os.getenv("KC_CLIENT_SECRET", "2BcfsFrhh6WWwMQMhxfyjweZuLdWmfpr")
KC_GRANT_TYPE = os.getenv("KC_GRANT_TYPE", "password")
KC_USERNAME = os.getenv("KC_USERNAME", os.getenv("USER_EMAIL", "postman_user2@example.com"))
KC_PASSWORD = os.getenv("KC_PASSWORD", "password123")
KC_ADMIN_USER = os.getenv("KC_ADMIN_USER", "admin")
KC_ADMIN_PASS = os.getenv("KC_ADMIN_PASS", "admin")

# Avatar size limit configuration (5MB default)
AVATAR_MAX_FILE_SIZE = int(os.getenv("AVATAR_MAX_FILE_SIZE_BYTES", 5 * 1024 * 1024))

logger.info(f"Storage App URL: {STORAGE_APP_URL}")
logger.info(f"Keycloak URL: {KC_URL}")
logger.info(f"Keycloak Realm: {KC_REALM}")
logger.info(f"Test User: {KC_USERNAME}")

# ============================================================================
# Test Execution
# ============================================================================

def main():
    """Main test runner orchestrating all avatar E2E tests."""
    
    logger.info("=" * 80)
    logger.info("Starting E2E Tests for Storage Service - Avatar API")
    logger.info("=" * 80)
    
    # Cleanup: Delete test user from Keycloak if exists (from previous run)
    logger.info("\n[SETUP] Cleaning up test user from previous run...")
    try:
        # delete_keycloak_user(KC_URL, KC_REALM, KC_USERNAME, KC_ADMIN_USER, KC_ADMIN_PASS)
        pass
    except Exception as e:
        logger.warning(f"Cleanup skipped: {e}")
    
    # Get user token for authenticated tests
    logger.info("\n[SETUP] Obtaining access token from Keycloak...")
    try:
        user_token = get_user_token(
            kc_url=KC_URL,
            realm=KC_REALM,
            client_id=KC_CLIENT_ID,
            client_secret=KC_CLIENT_SECRET,
            username=KC_USERNAME,
            password=KC_PASSWORD,
            grant_type=KC_GRANT_TYPE
        )
        logger.info(f"✓ Got access token for user '{KC_USERNAME}'")
    except Exception as e:
        logger.error(f"Failed to get user token: {e}")
        logger.error("Ensure Keycloak is running and user exists")
        return False
    
    auth_header = {"Authorization": f"Bearer {user_token}"}
    
    # Generate test image files
    logger.info("\n[SETUP] Generating test image files...")
    jpeg_content = generate_valid_jpeg()
    png_content = generate_valid_png()
    webp_content = generate_valid_webp()
    empty_content = generate_empty_file()
    invalid_content = generate_invalid_file()
    oversized_content = generate_oversized_file(AVATAR_MAX_FILE_SIZE + 1024)
    truncated_content = generate_truncated_png()
    
    logger.info(f"✓ Generated test images:")
    logger.info(f"  - JPEG: {len(jpeg_content)} bytes")
    logger.info(f"  - PNG: {len(png_content)} bytes")
    logger.info(f"  - WebP: {len(webp_content)} bytes")
    logger.info(f"  - Empty: {len(empty_content)} bytes")
    logger.info(f"  - Invalid: {len(invalid_content)} bytes")
    logger.info(f"  - Oversized: {len(oversized_content)} bytes")
    logger.info(f"  - Truncated PNG: {len(truncated_content)} bytes")
    
    test_results = {
        'passed': [],
        'failed': [],
        'skipped': []
    }
    
    try:
        # =====================================================================
        # Group 1: GET /api/v1/avatars/me - Get Avatar Metadata
        # =====================================================================
        logger.info("\n" + "=" * 80)
        logger.info("Test Group 1: GET /api/v1/avatars/me - Get Avatar Metadata")
        logger.info("=" * 80)
        
        # Test 1.1: Get avatar metadata (no avatar initially)
        logger.info("\n[Test 1.1] Get avatar metadata - no avatar (initial state)")
        try:
            test_get_avatar_success_without_avatar(STORAGE_APP_URL, auth_header)
            test_results['passed'].append("GET /me - no avatar")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("GET /me - no avatar", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("GET /me - no avatar", str(e)))
        
        # Test 1.2: Get avatar unauthorized
        logger.info("\n[Test 1.2] Get avatar metadata - unauthorized")
        try:
            test_get_avatar_unauthorized()
            test_results['passed'].append("GET /me - unauthorized")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("GET /me - unauthorized", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("GET /me - unauthorized", str(e)))
        
        # =====================================================================
        # Group 2: PUT /api/v1/avatars/me - Upload Avatar (positive scenarios)
        # =====================================================================
        logger.info("\n" + "=" * 80)
        logger.info("Test Group 2: PUT /api/v1/avatars/me - Upload Avatar (Positive)")
        logger.info("=" * 80)
        
        # Test 2.1: Upload JPEG
        logger.info("\n[Test 2.1] Upload valid JPEG image")
        try:
            test_upload_avatar_success_jpeg(STORAGE_APP_URL, auth_header, jpeg_content)
            test_results['passed'].append("PUT /me - upload JPEG")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("PUT /me - upload JPEG", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("PUT /me - upload JPEG", str(e)))
        
        # Test 2.2: Get avatar with uploaded content
        logger.info("\n[Test 2.2] Get avatar metadata - with avatar")
        try:
            test_get_avatar_success_with_avatar(STORAGE_APP_URL, auth_header)
            test_results['passed'].append("GET /me - with avatar")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("GET /me - with avatar", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("GET /me - with avatar", str(e)))
        
        # =====================================================================
        # Group 3: GET /api/v1/avatars/me/content - Download Avatar Content
        # =====================================================================
        logger.info("\n" + "=" * 80)
        logger.info("Test Group 3: GET /api/v1/avatars/me/content - Download Avatar")
        logger.info("=" * 80)
        
        # Test 3.1: Get avatar content (with avatar)
        logger.info("\n[Test 3.1] Download avatar content - success")
        try:
            test_get_avatar_content_success(STORAGE_APP_URL, auth_header)
            test_results['passed'].append("GET /me/content - success")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("GET /me/content - success", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("GET /me/content - success", str(e)))
        
        # Test 3.2: Get avatar content unauthorized
        logger.info("\n[Test 3.2] Download avatar content - unauthorized")
        try:
            test_get_avatar_content_unauthorized()
            test_results['passed'].append("GET /me/content - unauthorized")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("GET /me/content - unauthorized", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("GET /me/content - unauthorized", str(e)))
        
        # =====================================================================
        # Group 4: PUT /api/v1/avatars/me - Upload Avatar (Negative scenarios)
        # =====================================================================
        logger.info("\n" + "=" * 80)
        logger.info("Test Group 4: PUT /api/v1/avatars/me - Upload Avatar (Negative)")
        logger.info("=" * 80)
        
        # Test 4.1: Upload empty file
        logger.info("\n[Test 4.1] Upload empty file - 400 Bad Request")
        try:
            test_upload_avatar_empty_file(STORAGE_APP_URL, auth_header, empty_content)
            test_results['passed'].append("PUT /me - empty file (400)")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("PUT /me - empty file (400)", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("PUT /me - empty file (400)", str(e)))
        
        # Test 4.2: Upload PNG (to have a valid avatar for subsequent tests)
        logger.info("\n[Test 4.2] Upload valid PNG image")
        try:
            test_upload_avatar_success_png(STORAGE_APP_URL, auth_header, png_content)
            test_results['passed'].append("PUT /me - upload PNG")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("PUT /me - upload PNG", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("PUT /me - upload PNG", str(e)))
        
        # Test 4.3: Upload unsupported media type
        logger.info("\n[Test 4.3] Upload unsupported media type - 415")
        try:
            test_upload_avatar_unsupported_media_type(STORAGE_APP_URL, auth_header, invalid_content)
            test_results['passed'].append("PUT /me - unsupported type (415)")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("PUT /me - unsupported type (415)", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("PUT /me - unsupported type (415)", str(e)))
        
        # Test 4.4: Upload file exceeding size limit
        logger.info("\n[Test 4.4] Upload file exceeding size limit - 413")
        try:
            test_upload_avatar_exceeds_size_limit(STORAGE_APP_URL, auth_header, oversized_content)
            test_results['passed'].append("PUT /me - oversized (413)")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("PUT /me - oversized (413)", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("PUT /me - oversized (413)", str(e)))
        
        # Test 4.5: Upload truncated PNG signature
        logger.info("\n[Test 4.5] Upload truncated image signature - 415")
        try:
            test_upload_avatar_truncated_signature(STORAGE_APP_URL, auth_header, truncated_content)
            test_results['passed'].append("PUT /me - truncated signature (415)")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("PUT /me - truncated signature (415)", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("PUT /me - truncated signature (415)", str(e)))
        
        # Test 4.6: Upload WebP (to have valid avatar for content test)
        logger.info("\n[Test 4.6] Upload valid WebP image")
        try:
            test_upload_avatar_success_webp(STORAGE_APP_URL, auth_header, webp_content)
            test_results['passed'].append("PUT /me - upload WebP")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("PUT /me - upload WebP", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("PUT /me - upload WebP", str(e)))
        
        # Test 4.7: Upload unauthorized
        logger.info("\n[Test 4.7] Upload avatar - unauthorized")
        try:
            test_upload_avatar_unauthorized()
            test_results['passed'].append("PUT /me - unauthorized")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("PUT /me - unauthorized", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("PUT /me - unauthorized", str(e)))
        
        # =====================================================================
        # Group 5: DELETE /api/v1/avatars/me - Delete Avatar
        # =====================================================================
        logger.info("\n" + "=" * 80)
        logger.info("Test Group 5: DELETE /api/v1/avatars/me - Delete Avatar")
        logger.info("=" * 80)
        
        # Test 5.1: Delete avatar
        logger.info("\n[Test 5.1] Delete avatar - success")
        try:
            test_delete_avatar_success(STORAGE_APP_URL, auth_header)
            test_results['passed'].append("DELETE /me - success")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("DELETE /me - success", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("DELETE /me - success", str(e)))
        
        # Test 5.2: Get avatar content after deletion (should 404)
        logger.info("\n[Test 5.2] Download avatar content - not found (404)")
        try:
            test_get_avatar_content_not_found(STORAGE_APP_URL, auth_header)
            test_results['passed'].append("GET /me/content - 404 not found")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("GET /me/content - 404 not found", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("GET /me/content - 404 not found", str(e)))
        
        # Test 5.3: Delete avatar again (idempotent)
        logger.info("\n[Test 5.3] Delete avatar again - idempotent (204)")
        try:
            test_delete_avatar_not_found_idempotent(STORAGE_APP_URL, auth_header)
            test_results['passed'].append("DELETE /me - idempotent")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("DELETE /me - idempotent", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("DELETE /me - idempotent", str(e)))
        
        # Test 5.4: Delete unauthorized
        logger.info("\n[Test 5.4] Delete avatar - unauthorized")
        try:
            test_delete_avatar_unauthorized()
            test_results['passed'].append("DELETE /me - unauthorized")
        except AssertionError as e:
            logger.error(f"✗ Test failed: {e}")
            test_results['failed'].append(("DELETE /me - unauthorized", str(e)))
        except Exception as e:
            logger.error(f"✗ Test error: {e}")
            test_results['failed'].append(("DELETE /me - unauthorized", str(e)))
        
        # =========================================================================
        # Test Group 6: SSO Integration Test
        # =========================================================================
        logger.info("\n" + "=" * 80)
        logger.info("Test Group 6: SSO Integration Test (M2M / SSO)")
        logger.info("=" * 80)
        
        from controller.sso_end_to_end_test import check_sso_docker_containers
        
        is_isolated_testing = "8444" in KC_URL
        if is_isolated_testing or not check_sso_docker_containers():
            logger.warning("SSO E2E TEST SKIPPED: Required Docker containers are not running or Keycloak is in isolated mode.")
            test_results['skipped'].append("SSO / M2M Integration Test")
        else:
            try:
                test_sso_integration(
                    kc_url=KC_URL,
                    realm=KC_REALM,
                    client_id="mail-and-media-shop-app",
                    client_secret=KC_CLIENT_SECRET,
                    username=KC_USERNAME,
                    password=KC_PASSWORD,
                    api_url=API_APP_URL,
                    storage_url=STORAGE_APP_URL,
                    token_helper_fn=get_user_token
                )
                test_results['passed'].append("SSO / M2M Integration Test")
            except Exception as e:
                logger.error(f"✗ SSO Test error: {e}")
                test_results['failed'].append(("SSO / M2M Integration Test", str(e)))

    except Exception as e:
        logger.error(f"Unexpected error during test execution: {e}", exc_info=True)
        return False
    finally:
        # Cleanup: Delete test avatar and user
        logger.info("\n" + "=" * 80)
        logger.info("CLEANUP")
        logger.info("=" * 80)
        
        logger.info("\n[CLEANUP] Deleting test avatar...")
        try:
            requests.delete(f"{STORAGE_APP_URL}/api/v1/avatars/me", headers=auth_header, verify=False)
            logger.info("✓ Avatar deleted")
        except Exception as e:
            logger.warning(f"Failed to delete avatar: {e}")
        
        logger.info("\n[CLEANUP] Deleting test user from Keycloak...")
        try:
            # delete_keycloak_user(KC_URL, KC_REALM, KC_USERNAME, KC_ADMIN_USER, KC_ADMIN_PASS)
            logger.info("✓ User deletion skipped (retained for future runs)")
        except Exception as e:
            logger.warning(f"Failed to delete user: {e}")
    
    # =========================================================================
    # Test Summary
    # =========================================================================
    logger.info("\n" + "=" * 80)
    logger.info("TEST SUMMARY")
    logger.info("=" * 80)
    
    passed = len(test_results['passed'])
    failed = len(test_results['failed'])
    skipped = len(test_results['skipped'])
    total = passed + failed + skipped
    
    logger.info(f"\nTotal Tests: {total}")
    logger.info(f"✓ Passed: {passed}")
    logger.error(f"✗ Failed: {failed}" if failed > 0 else f"✗ Failed: {failed}")
    logger.info(f"⊝ Skipped: {skipped}")
    
    if test_results['passed']:
        logger.info("\n[PASSED TESTS]")
        for test_name in test_results['passed']:
            logger.info(f"  ✓ {test_name}")
    
    if test_results['failed']:
        logger.error("\n[FAILED TESTS]")
        for test_name, error in test_results['failed']:
            logger.error(f"  ✗ {test_name}")
            logger.error(f"    Error: {error}")
    
    if test_results['skipped']:
        logger.info("\n[SKIPPED TESTS]")
        for test_name in test_results['skipped']:
            logger.info(f"  ⊝ {test_name}")
    
    logger.info("\n" + "=" * 80)
    
    # Return success only if all tests passed
    return failed == 0


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
