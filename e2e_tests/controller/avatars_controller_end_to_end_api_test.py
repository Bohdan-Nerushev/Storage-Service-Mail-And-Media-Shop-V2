import requests
import logging
import io
from typing import Optional, Tuple

logger = logging.getLogger(__name__)


# ============================================================================
# GET /api/v1/avatars/me - Get Avatar Metadata
# ============================================================================

def test_get_avatar_success_with_avatar(base_url: str, auth_header: dict):
    """
    Positive: User has avatar → 200 AvatarResponse with hasAvatar=true
    
    Precondition: Avatar must be uploaded first (call upload test before this)
    """
    url = f"{base_url}/api/v1/avatars/me"
    response = requests.get(url, headers=auth_header)
    
    assert response.status_code == 200, f"Expected 200, got {response.status_code}: {response.text}"
    data = response.json()
    
    assert data.get("hasAvatar") is True, "Avatar should exist"
    assert data.get("version") >= 0, "Version should be non-negative"
    assert data.get("contentType") in ["image/jpeg", "image/png", "image/webp"], \
        f"Invalid content type: {data.get('contentType')}"
    assert data.get("size") > 0, "Size should be positive"
    assert data.get("updatedAt") is not None, "updatedAt should be present"
    
    logger.info("✓ test_get_avatar_success_with_avatar passed")
    return data


def test_get_avatar_success_without_avatar(base_url: str, auth_header: dict):
    """
    Positive: User has no avatar → 200 AvatarResponse with hasAvatar=false
    
    Precondition: Avatar should be deleted first, or this should be first avatar operation
    """
    url = f"{base_url}/api/v1/avatars/me"
    response = requests.get(url, headers=auth_header)
    
    assert response.status_code == 200, f"Expected 200, got {response.status_code}: {response.text}"
    data = response.json()
    
    assert data.get("hasAvatar") is False, "Avatar should not exist"
    assert data.get("version") == 0, "Version should be 0"
    assert data.get("contentType") is None, "contentType should be null"
    assert data.get("size") == 0, "Size should be 0"
    assert data.get("updatedAt") is None, "updatedAt should be null"
    
    logger.info("✓ test_get_avatar_success_without_avatar passed")
    return data


def test_get_avatar_unauthorized():
    """
    Negative: No authorization → 401 Unauthorized
    """
    # Use dummy URL - doesn't matter since we won't have auth
    base_url = "http://localhost:8080"
    url = f"{base_url}/api/v1/avatars/me"
    response = requests.get(url)  # No auth header
    
    assert response.status_code == 401, f"Expected 401, got {response.status_code}"
    
    logger.info("✓ test_get_avatar_unauthorized passed")


# ============================================================================
# GET /api/v1/avatars/me/content - Get Avatar Binary Content
# ============================================================================

def test_get_avatar_content_success(base_url: str, auth_header: dict):
    """
    Positive: User has avatar → 200 binary stream with cache headers
    
    Precondition: Avatar must be uploaded first
    """
    url = f"{base_url}/api/v1/avatars/me/content"
    response = requests.get(url, headers=auth_header)
    
    assert response.status_code == 200, f"Expected 200, got {response.status_code}: {response.text}"
    
    # Verify content headers
    assert response.headers.get("Content-Type") in ["image/jpeg", "image/png", "image/webp"], \
        f"Invalid Content-Type: {response.headers.get('Content-Type')}"
    
    assert "Cache-Control" in response.headers, "Cache-Control header missing"
    assert response.headers.get("Cache-Control") == "max-age=300, private", \
        f"Unexpected Cache-Control: {response.headers.get('Cache-Control')}"
    
    assert "ETag" in response.headers, "ETag header missing"
    vary_header = response.headers.get("Vary") or ""
    assert "Authorization" in [v.strip() for v in vary_header.split(",")], \
        f"Unexpected Vary header: {vary_header}"
    
    # Verify content length
    content_length = response.headers.get("Content-Length")
    assert content_length is not None, "Content-Length header missing"
    assert int(content_length) > 0, "Content-Length should be positive"
    
    # Verify binary content
    assert len(response.content) > 0, "Response should contain image data"
    
    logger.info("✓ test_get_avatar_content_success passed")
    return response.content


def test_get_avatar_content_not_found(base_url: str, auth_header: dict):
    """
    Negative: User has no avatar → 404 ResourceNotFoundException
    
    Precondition: Avatar should be deleted first
    """
    url = f"{base_url}/api/v1/avatars/me/content"
    response = requests.get(url, headers=auth_header)
    
    assert response.status_code == 404, f"Expected 404, got {response.status_code}"
    
    # Verify error response structure
    data = response.json()
    assert data.get("status") == 404, "Status should be 404"
    assert "not found" in data.get("message", "").lower(), "Message should mention 'not found'"
    
    logger.info("✓ test_get_avatar_content_not_found passed")


def test_get_avatar_content_unauthorized():
    """
    Negative: No authorization → 401 Unauthorized
    """
    base_url = "http://localhost:8080"
    url = f"{base_url}/api/v1/avatars/me/content"
    response = requests.get(url)  # No auth header
    
    assert response.status_code == 401, f"Expected 401, got {response.status_code}"
    
    logger.info("✓ test_get_avatar_content_unauthorized passed")


# ============================================================================
# PUT /api/v1/avatars/me - Upload/Replace Avatar
# ============================================================================

def test_upload_avatar_success_jpeg(base_url: str, auth_header: dict, jpeg_content: bytes):
    """
    Positive: Upload valid JPEG → 200 AvatarResponse
    """
    url = f"{base_url}/api/v1/avatars/me"
    
    files = {'file': ('avatar.jpg', jpeg_content, 'image/jpeg')}
    response = requests.put(url, files=files, headers=auth_header)
    
    assert response.status_code == 200, f"Expected 200, got {response.status_code}: {response.text}"
    
    data = response.json()
    assert data.get("hasAvatar") is True, "Avatar should exist"
    assert data.get("contentType") == "image/jpeg", "Content type should be JPEG"
    assert data.get("version") >= 0, "Version should be non-negative"
    assert data.get("size") > 0, "Size should be positive"
    
    logger.info("✓ test_upload_avatar_success_jpeg passed")
    return data


def test_upload_avatar_success_png(base_url: str, auth_header: dict, png_content: bytes):
    """
    Positive: Upload valid PNG → 200 AvatarResponse
    """
    url = f"{base_url}/api/v1/avatars/me"
    
    files = {'file': ('avatar.png', png_content, 'image/png')}
    response = requests.put(url, files=files, headers=auth_header)
    
    assert response.status_code == 200, f"Expected 200, got {response.status_code}: {response.text}"
    
    data = response.json()
    assert data.get("hasAvatar") is True, "Avatar should exist"
    assert data.get("contentType") == "image/png", "Content type should be PNG"
    assert data.get("version") >= 0, "Version should be non-negative"
    assert data.get("size") > 0, "Size should be positive"
    
    logger.info("✓ test_upload_avatar_success_png passed")
    return data


def test_upload_avatar_success_webp(base_url: str, auth_header: dict, webp_content: bytes):
    """
    Positive: Upload valid WebP → 200 AvatarResponse
    """
    url = f"{base_url}/api/v1/avatars/me"
    
    files = {'file': ('avatar.webp', webp_content, 'image/webp')}
    response = requests.put(url, files=files, headers=auth_header)
    
    assert response.status_code == 200, f"Expected 200, got {response.status_code}: {response.text}"
    
    data = response.json()
    assert data.get("hasAvatar") is True, "Avatar should exist"
    assert data.get("contentType") == "image/webp", "Content type should be WebP"
    assert data.get("version") >= 0, "Version should be non-negative"
    assert data.get("size") > 0, "Size should be positive"
    
    logger.info("✓ test_upload_avatar_success_webp passed")
    return data


def test_upload_avatar_empty_file(base_url: str, auth_header: dict, empty_content: bytes):
    """
    Negative: Upload empty file → 400 Bad Request (AvatarValidationException)
    """
    url = f"{base_url}/api/v1/avatars/me"
    
    files = {'file': ('empty.jpg', empty_content, 'image/jpeg')}
    response = requests.put(url, files=files, headers=auth_header)
    
    assert response.status_code == 400, f"Expected 400, got {response.status_code}"
    
    data = response.json()
    assert data.get("status") == 400, "Status should be 400"
    assert "empty" in data.get("message", "").lower(), "Message should mention 'empty'"
    
    logger.info("✓ test_upload_avatar_empty_file passed")


def test_upload_avatar_unreadable_file(base_url: str, auth_header: dict):
    """
    Negative: Upload file that cannot be read → 400 Bad Request
    
    Note: This test is difficult to simulate in requests as it handles file I/O.
    We simulate by providing invalid file-like object or minimal bytes.
    """
    url = f"{base_url}/api/v1/avatars/me"
    
    # Try with corrupt file (text, not image)
    corrupt_content = b'This is not a valid image'
    files = {'file': ('corrupt.jpg', corrupt_content, 'image/jpeg')}
    response = requests.put(url, files=files, headers=auth_header)
    
    # Should reject as unsupported media type (not BAD_REQUEST for read error)
    # But we test the principle that invalid content fails
    assert response.status_code in [400, 415], \
        f"Expected 400 or 415, got {response.status_code}"
    
    logger.info("✓ test_upload_avatar_unreadable_file passed")


def test_upload_avatar_exceeds_size_limit(base_url: str, auth_header: dict, oversized_content: bytes):
    """
    Negative: Upload file exceeding size limit → 413 Payload Too Large
    """
    url = f"{base_url}/api/v1/avatars/me"
    
    files = {'file': ('oversized.jpg', oversized_content, 'image/jpeg')}
    response = requests.put(url, files=files, headers=auth_header)
    
    # Can be 413 (direct file size check) or 413 from exception handler
    assert response.status_code in [413, 400], \
        f"Expected 413 or 400 (size exceeded), got {response.status_code}"
    
    if response.status_code == 413:
        data = response.json()
        assert data.get("status") == 413, "Status should be 413"
        assert "size" in data.get("message", "").lower(), "Message should mention 'size'"
    
    logger.info("✓ test_upload_avatar_exceeds_size_limit passed")


def test_upload_avatar_unsupported_media_type(base_url: str, auth_header: dict, invalid_content: bytes):
    """
    Negative: Upload unsupported file type (no valid image signature) → 415 Unsupported Media Type
    """
    url = f"{base_url}/api/v1/avatars/me"
    
    files = {'file': ('invalid.jpg', invalid_content, 'image/jpeg')}
    response = requests.put(url, files=files, headers=auth_header)
    
    assert response.status_code == 415, f"Expected 415, got {response.status_code}"
    
    data = response.json()
    assert data.get("status") == 415, "Status should be 415"
    assert "JPEG" in data.get("message", "") or "PNG" in data.get("message", "") or "WebP" in data.get("message", ""), \
        "Message should mention supported formats"
    
    logger.info("✓ test_upload_avatar_unsupported_media_type passed")


def test_upload_avatar_truncated_signature(base_url: str, auth_header: dict, truncated_content: bytes):
    """
    Negative: Upload file with truncated/incomplete image signature → 415 Unsupported Media Type
    """
    url = f"{base_url}/api/v1/avatars/me"
    
    files = {'file': ('truncated.png', truncated_content, 'image/png')}
    response = requests.put(url, files=files, headers=auth_header)
    
    assert response.status_code == 415, f"Expected 415, got {response.status_code}"
    
    data = response.json()
    assert data.get("status") == 415, "Status should be 415"
    
    logger.info("✓ test_upload_avatar_truncated_signature passed")


def test_upload_avatar_conflict_concurrent_modification(
    base_url: str,
    auth_header: dict,
    jpeg_content: bytes,
    png_content: bytes
):
    """
    Negative: Concurrent modification conflict → 409 Conflict (AvatarConflictException)
    
    This test simulates concurrent modification by:
    1. Upload first avatar (creates metadata)
    2. Simulate concurrent update via direct DB modification (not via API)
    3. Attempt to update avatar again
    
    Since we can't directly modify DB, we'll test this scenario is possible by
    documenting the condition and attempting rapid successive updates with version checks.
    """
    url = f"{base_url}/api/v1/avatars/me"
    
    # First upload
    files1 = {'file': ('avatar1.jpg', jpeg_content, 'image/jpeg')}
    response1 = requests.put(url, files=files1, headers=auth_header)
    assert response1.status_code == 200, "First upload should succeed"
    
    # The conflict would occur during a concurrent modification at DB level
    # This is difficult to test without direct DB access or multi-threading
    # We document this as a known limitation of E2E testing
    
    logger.info("⚠ test_upload_avatar_conflict_concurrent_modification - requires DB access to fully test")


def test_upload_avatar_unauthorized():
    """
    Negative: No authorization → 401 Unauthorized
    """
    base_url = "http://localhost:8080"
    url = f"{base_url}/api/v1/avatars/me"
    
    files = {'file': ('avatar.jpg', b'fake', 'image/jpeg')}
    response = requests.put(url, files=files)  # No auth header
    
    assert response.status_code == 401, f"Expected 401, got {response.status_code}"
    
    logger.info("✓ test_upload_avatar_unauthorized passed")


# ============================================================================
# DELETE /api/v1/avatars/me - Delete Avatar
# ============================================================================

def test_delete_avatar_success(base_url: str, auth_header: dict):
    """
    Positive: Delete existing avatar → 204 No Content
    
    Precondition: Avatar must be uploaded first
    """
    url = f"{base_url}/api/v1/avatars/me"
    response = requests.delete(url, headers=auth_header)
    
    assert response.status_code == 204, f"Expected 204, got {response.status_code}: {response.text}"
    assert response.text == "" or len(response.content) == 0, "Response body should be empty"
    
    logger.info("✓ test_delete_avatar_success passed")


def test_delete_avatar_not_found_idempotent(base_url: str, auth_header: dict):
    """
    Positive: Delete non-existent avatar (idempotent) → 204 No Content
    
    Precondition: Avatar should already be deleted or never existed
    This tests idempotency - deleting twice should both return 204
    """
    url = f"{base_url}/api/v1/avatars/me"
    response = requests.delete(url, headers=auth_header)
    
    # Idempotent: should return 204 even if avatar doesn't exist
    assert response.status_code == 204, f"Expected 204, got {response.status_code}: {response.text}"
    assert response.text == "" or len(response.content) == 0, "Response body should be empty"
    
    logger.info("✓ test_delete_avatar_not_found_idempotent passed")


def test_delete_avatar_unauthorized():
    """
    Negative: No authorization → 401 Unauthorized
    """
    base_url = "http://localhost:8080"
    url = f"{base_url}/api/v1/avatars/me"
    response = requests.delete(url)  # No auth header
    
    assert response.status_code == 401, f"Expected 401, got {response.status_code}"
    
    logger.info("✓ test_delete_avatar_unauthorized passed")
