-- Add logo_filename column to corporation table
-- This allows storing local logo files instead of external URLs

ALTER TABLE corporation 
ADD COLUMN logo_filename VARCHAR(255) AFTER logo_url;

-- Add comment to the new column
ALTER TABLE corporation 
MODIFY COLUMN logo_filename VARCHAR(255) COMMENT 'Local filename for corporation logo stored in /images/logos/';