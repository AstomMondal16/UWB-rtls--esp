"""Position validation tests without a Tk window or hardware."""
import math
import time
import unittest
from types import SimpleNamespace

from localization_dashboard import LocalizationDashboard, trilaterate_2d


class PositionValidationTest(unittest.TestCase):
    def setUp(self):
        self.anchors = [dict(mac=str(i), role="anchor", x=x, y=y, z=0)
                        for i, (x, y) in enumerate(((0, 0), (4, 0), (0, 4)))]

    def solve(self, distances, age=0, z=0):
        fixture = SimpleNamespace(
            devices=self.anchors + [dict(mac="tag", role="tag", z=z)],
            ranges={(a["mac"], "tag"): dict(distance=d, time=time.monotonic() - age)
                    for a, d in zip(self.anchors, distances)},
            positions={"tag": dict(x=99, y=99)})
        LocalizationDashboard.solve_positions(fixture)
        return fixture.positions

    def test_inconsistent_ranges_remove_cached_position(self):
        self.assertEqual({}, self.solve([2, 2, 20]))

    def test_valid_manual_negative_frame(self):
        distances = [math.hypot(a["x"] + 1, a["y"] - 2) for a in self.anchors]
        position = self.solve(distances)["tag"]
        self.assertAlmostEqual(-1, position["x"])
        self.assertAlmostEqual(2, position["y"])

    def test_saved_heights_are_used(self):
        self.anchors[0]["z"] = 5
        distances = [math.hypot(a["x"] - 2, a["y"] - 3, a["z"] - 1.5)
                     for a in self.anchors]
        position = self.solve(distances, z=1.5)["tag"]
        self.assertAlmostEqual(2, position["x"])
        self.assertAlmostEqual(3, position["y"])

    def test_old_future_and_nonfinite_readings_are_rejected(self):
        for distances, age in (([3, 3, 3], 4), ([3, 3, 3], -1), ([3, 3, math.nan], 0)):
            with self.subTest(distances=distances, age=age):
                self.assertEqual({}, self.solve(distances, age))

    def test_nearly_collinear_geometry_is_rejected(self):
        with self.assertRaises(ValueError):
            trilaterate_2d([(0, 0, 3), (4, 0, 3), (8, 1e-7, 7)])


if __name__ == "__main__":
    unittest.main()
