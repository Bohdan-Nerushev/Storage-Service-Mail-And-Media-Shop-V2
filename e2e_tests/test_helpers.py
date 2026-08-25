import io
import os
import logging
from pathlib import Path
from PIL import Image
import requests
import urllib3

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

logger = logging.getLogger(__name__)


# ============================================================================
# Image Generation Helpers
# ============================================================================

def generate_valid_jpeg() -> bytes:
    """Generate valid JPEG image with correct magic bytes."""
    img = Image.new('RGB', (100, 100), color='red')
    buffer = io.BytesIO()
    img.save(buffer, format='JPEG')
    buffer.seek(0)
    return buffer.getvalue()


def generate_valid_png() -> bytes:
    """Generate valid PNG image with correct magic bytes."""
    img = Image.new('RGB', (100, 100), color='green')
    buffer = io.BytesIO()
    img.save(buffer, format='PNG')
    buffer.seek(0)
    return buffer.getvalue()


def generate_valid_webp() -> bytes:
    """Generate valid WebP image with correct magic bytes."""
    img = Image.new('RGB', (100, 100), color='blue')
    buffer = io.BytesIO()
    img.save(buffer, format='WebP')
    buffer.seek(0)
    return buffer.getvalue()


def generate_empty_file() -> bytes:
    """Generate empty file (0 bytes)."""
    return b''


def generate_invalid_file() -> bytes:
    """Generate file that's not a valid image (text content)."""
    return b'This is not a valid image file content'


def generate_oversized_file(size_bytes: int) -> bytes:
    """Generate file exceeding size limit."""
    # Create a minimal JPEG header + padding to reach desired size
    jpeg_header = bytes([0xFF, 0xD8, 0xFF, 0xE0])  # JPEG header
    padding = b'\x00' * (size_bytes - len(jpeg_header))
    return jpeg_header + padding


def generate_truncated_png() -> bytes:
    """Generate truncated PNG (only first few bytes of header)."""
    # PNG magic bytes: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
    # Return only first 5 bytes (incomplete)
    return bytes([0x89, 0x50, 0x4E, 0x47, 0x0D])


def generate_corrupted_jpeg() -> bytes:
    """Generate file with JPEG signature but corrupted content."""
    # Valid JPEG header but invalid rest
    jpeg_header = bytes([0xFF, 0xD8, 0xFF, 0xDB])
    padding = b'\x00' * 100
    return jpeg_header + padding


# ============================================================================
# Keycloak Authentication Helpers
# ============================================================================

def get_user_token(
    kc_url: str,
    realm: str,
    client_id: str,
    client_secret: str,
    username: str,
    password: str,
    grant_type: str = "password"
) -> str:
    """
    Get access token from Keycloak for a user.
    
    Args:
        kc_url: Keycloak base URL (e.g., 'https://localhost:8443')
        realm: Keycloak realm name
        client_id: OAuth2 client ID
        client_secret: OAuth2 client secret
        username: Username/email
        password: User password
        grant_type: Grant type (default: 'password')
    
    Returns:
        Access token string
    """
    token_url = f"{kc_url.rstrip('/')}/realms/{realm}/protocol/openid-connect/token"
    payload = {
        "client_id": client_id,
        "client_secret": client_secret,
        "username": username,
        "password": password,
        "grant_type": grant_type
    }
    
    try:
        response = requests.post(token_url, data=payload, verify=False, timeout=10)
        if response.status_code != 200:
            logger.error(f"Failed to get token from Keycloak: HTTP {response.status_code}")
            logger.error(f"Response: {response.text}")
            raise RuntimeError(f"Keycloak token request failed with status {response.status_code}")
        
        token = response.json().get("access_token")
        if not token:
            logger.error("No access_token in Keycloak response")
            raise RuntimeError("No access_token in Keycloak response")
        
        logger.info(f"Successfully obtained token for user '{username}'")
        return token
    except requests.exceptions.RequestException as e:
        logger.error(f"Error communicating with Keycloak: {e}")
        raise


def get_admin_token(
    kc_url: str,
    admin_user: str = "admin",
    admin_pass: str = "admin"
) -> str:
    """
    Get admin token from Keycloak for administrative operations.
    
    Args:
        kc_url: Keycloak base URL (e.g., 'https://localhost:8443')
        admin_user: Admin username (default: 'admin')
        admin_pass: Admin password (default: 'admin')
    
    Returns:
        Access token string for admin operations
    """
    token_url = f"{kc_url.rstrip('/')}/realms/master/protocol/openid-connect/token"
    payload = {
        "client_id": "admin-cli",
        "username": admin_user,
        "password": admin_pass,
        "grant_type": "password"
    }
    
    try:
        response = requests.post(token_url, data=payload, verify=False, timeout=10)
        if response.status_code != 200:
            logger.error(f"Failed to get admin token: HTTP {response.status_code}")
            raise RuntimeError(f"Admin token request failed with status {response.status_code}")
        
        token = response.json().get("access_token")
        if not token:
            raise RuntimeError("No access_token in admin token response")
        
        logger.info("Successfully obtained admin token")
        return token
    except requests.exceptions.RequestException as e:
        logger.error(f"Error communicating with Keycloak: {e}")
        raise


# ============================================================================
# Keycloak User Management Helpers
# ============================================================================

def delete_keycloak_user(
    kc_url: str,
    realm: str,
    user_email: str,
    admin_user: str = "admin",
    admin_pass: str = "admin"
) -> bool:
    """
    Delete a user from Keycloak by email.
    
    Args:
        kc_url: Keycloak base URL
        realm: Keycloak realm name
        user_email: Email of user to delete
        admin_user: Admin username
        admin_pass: Admin password
    
    Returns:
        True if user was deleted, False if not found or error occurred
    """
    try:
        admin_token = get_admin_token(kc_url, admin_user, admin_pass)
        
        # Search for user by email
        search_url = f"{kc_url.rstrip('/')}/admin/realms/{realm}/users?email={user_email}"
        headers = {"Authorization": f"Bearer {admin_token}"}
        
        response = requests.get(search_url, headers=headers, verify=False, timeout=10)
        if response.status_code != 200:
            logger.warning(f"Failed to search for user: HTTP {response.status_code}")
            return False
        
        users = response.json()
        if not users:
            logger.info(f"User '{user_email}' not found in Keycloak")
            return False
        
        user_id = users[0].get("id")
        if not user_id:
            logger.warning("User found but no ID present")
            return False
        
        # Delete the user
        delete_url = f"{kc_url.rstrip('/')}/admin/realms/{realm}/users/{user_id}"
        del_response = requests.delete(delete_url, headers=headers, verify=False, timeout=10)
        
        if del_response.status_code == 204:
            logger.info(f"Successfully deleted user '{user_email}' from Keycloak")
            return True
        else:
            logger.warning(f"Failed to delete user: HTTP {del_response.status_code}")
            return False
    except Exception as e:
        logger.warning(f"Error during user deletion: {e}")
        return False


# ============================================================================
# Response Validation Helpers
# ============================================================================

def validate_error_response(response_json: dict, expected_status: int) -> bool:
    """
    Validate that error response has required structure.
    
    Args:
        response_json: Parsed JSON response
        expected_status: Expected HTTP status code
    
    Returns:
        True if response has valid ErrorResponse structure
    """
    required_fields = ['timestamp', 'status', 'error', 'message', 'path']
    for field in required_fields:
        if field not in response_json:
            logger.error(f"Missing field in ErrorResponse: {field}")
            return False
    
    if response_json['status'] != expected_status:
        logger.error(f"Expected status {expected_status}, got {response_json['status']}")
        return False
    
    return True


def validate_avatar_response(response_json: dict, has_avatar: bool = True) -> bool:
    """
    Validate that avatar response has required structure.
    
    Args:
        response_json: Parsed JSON response
        has_avatar: Whether avatar should be present
    
    Returns:
        True if response has valid AvatarResponse structure
    """
    required_fields = ['hasAvatar', 'version', 'contentType', 'size', 'updatedAt']
    for field in required_fields:
        if field not in response_json:
            logger.error(f"Missing field in AvatarResponse: {field}")
            return False
    
    if response_json['hasAvatar'] != has_avatar:
        logger.error(f"Expected hasAvatar={has_avatar}, got {response_json['hasAvatar']}")
        return False
    
    if has_avatar:
        # When avatar exists, these should be populated
        if response_json['version'] <= 0 or not response_json['contentType'] or response_json['size'] <= 0:
            logger.error("Avatar metadata incomplete")
            return False
    else:
        # When no avatar, these should be default values
        if response_json['version'] != 0 or response_json['contentType'] is not None or response_json['size'] != 0:
            logger.error("Avatar metadata should be empty")
            return False
    
    return True
