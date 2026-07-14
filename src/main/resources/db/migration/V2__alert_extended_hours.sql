-- L5.3/Step 5: extended-hours opt-in. Never fire on a pre/after-hours print
-- from a 2%-of-volume feed unless the user explicitly asked for it.
ALTER TABLE alerts ADD COLUMN extended_hours BOOLEAN NOT NULL DEFAULT false;
