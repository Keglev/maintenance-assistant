-- Repeatable seed — the 10 machines of the demo plant (docs/DOMAIN-MODEL.md §3).
--
-- Repeatable rather than versioned (R__ rather than V2__) because this is reference data,
-- not a schema change: editing a machine's name or location should re-apply on the next
-- start instead of needing a new migration file. Flyway re-runs an R__ migration whenever
-- its checksum changes, and runs it after all pending versioned migrations.
--
-- Idempotent by construction: fixed UUIDs plus ON CONFLICT on the natural key. The ids are
-- literals and not generated, because the synthetic protocol corpus (PR 2) references
-- these machines and a re-seeded row that changed identity would orphan it.
--
-- Machines only. The ~150-protocol corpus is a separate deliverable and is deliberately
-- NOT generated here.
--
-- The mix is what the domain model asks for: two hydraulic presses of the same type but
-- 16 years apart (same fault, different cause — the reason age is modelled at all), mixed
-- control generations from a terminal-only 2007 filler to a 2021 SCADA line, and one
-- machine that will deliberately have no protocol for a common fault so the Mode B
-- ("no source in the corpus") path has something honest to demo against.

INSERT INTO machine (id, machine_no, name, type, manufacturer, year_built, control_type, location) VALUES
    -- Demo seed for US-1/US-2: error E-47 appears on this machine 2-3x in the corpus.
    ('0f9c5b01-0000-4000-8000-000000000001', 'PR-03', 'Presse 3',            'Hydraulikpresse',       'Schuler',      2005, 'PLC',      'Halle 1 - Linie A'),
    -- Same type, 16 years newer: SCADA instead of a bare PLC, different failure profile.
    ('0f9c5b01-0000-4000-8000-000000000002', 'PR-07', 'Presse 7',            'Hydraulikpresse',       'Schuler',      2021, 'SCADA',    'Halle 1 - Linie B'),
    ('0f9c5b01-0000-4000-8000-000000000003', 'FR-01', 'Fräse 1',             'CNC-Fräsmaschine',      'DMG Mori',     2016, 'CNC',      'Halle 2 - Linie C'),
    ('0f9c5b01-0000-4000-8000-000000000004', 'RB-02', 'Schweißroboter 2',    'Roboter-Schweißzelle',  'KUKA',         2018, 'PLC',      'Halle 2 - Linie C'),
    ('0f9c5b01-0000-4000-8000-000000000005', 'VP-01', 'Verpackungslinie 1',  'Verpackungsanlage',     'Multivac',     2014, 'SCADA',    'Halle 3 - Linie D'),
    -- Terminal-only legacy: no line integration, faults are read off the local display.
    ('0f9c5b01-0000-4000-8000-000000000006', 'KP-01', 'Kompressor 1',        'Schraubenkompressor',   'Atlas Copco',  2009, 'TERMINAL', 'Technikzentrale'),
    ('0f9c5b01-0000-4000-8000-000000000007', 'FB-04', 'Förderband 4',        'Gurtförderer',          'Interroll',    2012, 'PLC',      'Halle 3 - Linie D'),
    ('0f9c5b01-0000-4000-8000-000000000008', 'LS-01', 'Laserschneider 1',    'Laserschneidanlage',    'Trumpf',       2020, 'CNC',      'Halle 2 - Linie E'),
    -- The Mode B gap machine: the corpus will hold no protocol for its common fault.
    ('0f9c5b01-0000-4000-8000-000000000009', 'AB-02', 'Abfüllanlage 2',      'Abfüllanlage',          'Krones',       2007, 'TERMINAL', 'Halle 3 - Linie F'),
    ('0f9c5b01-0000-4000-8000-000000000010', 'SP-05', 'Spritzguss 5',        'Spritzgießmaschine',    'Arburg',       2019, 'SCADA',    'Halle 4 - Linie G')
ON CONFLICT (machine_no) DO UPDATE SET
    name         = EXCLUDED.name,
    type         = EXCLUDED.type,
    manufacturer = EXCLUDED.manufacturer,
    year_built   = EXCLUDED.year_built,
    control_type = EXCLUDED.control_type,
    location     = EXCLUDED.location;
